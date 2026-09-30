package com.icg.aliasdirectory.mainapi.portal;

import com.icg.aliasdirectory.mainapi.cancellation.AliasDeactivator;
import com.icg.aliasdirectory.mainapi.cancellation.CancellationRepository;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Baja de alias desde el portal, con doble control (Anexo §8.2).
 *
 * <p>Son dos actos separados y de dos personas distintas: un agente captura la
 * solicitud, que no cambia nada en el padrón, y otra persona de la misma entidad
 * la aprueba, y esa aprobación es la que ejecuta la baja. La segregación la
 * garantiza el motor con {@code ck_deactivation_request_dual_control}; aquí se
 * comprueba antes sólo para contestar un 409 legible en vez de un error de SQL.
 *
 * <p>El alcance es siempre OWN_BANK. ALL_BANKS da de baja el alias en entidades
 * que no son la del operador, y el catálogo lo separa en su propio permiso
 * ({@code ALIAS_BAJA_ALL_BANKS}); mientras no exista la pantalla que lo
 * justifique, el portal no lo ofrece.
 */
@ConditionalOnProperty(name = {"icg.kms.enabled", "icg.portal.enabled"}, havingValue = "true")
@Service
public class DeactivationService {

    private static final Logger log = LoggerFactory.getLogger(DeactivationService.class);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String SCOPE_OWN_BANK = "OWN_BANK";
    private static final int INBOX_LIMIT = 200;
    private static final int MIN_REASON_LENGTH = 10;
    private static final int MAX_REASON_LENGTH = 255;

    private final DeactivationRepository requests;
    private final CancellationRepository registrations;
    private final AliasDeactivator deactivator;
    private final TransitClient kms;
    private final String encryptionKey;
    private final Duration requestTtl;

    public DeactivationService(DeactivationRepository requests,
            CancellationRepository registrations, AliasDeactivator deactivator, TransitClient kms,
            @Value("${icg.kms.encryption-key}") String encryptionKey,
            @Value("${icg.portal.solicitud-baja-ttl-horas:72}") long requestTtlHours) {
        this.requests = requests;
        this.registrations = registrations;
        this.deactivator = deactivator;
        this.kms = kms;
        this.encryptionKey = encryptionKey;
        this.requestTtl = Duration.ofHours(requestTtlHours);
    }

    // ---- captura ----------------------------------------------------------

    @Transactional
    public void request(PortalDto.CancellationRequestInput input, PortalUser user) {
        String reason = requireReason(input.reason());

        if (!user.belongsToBank()) {
            throw PortalException.forbidden("Un usuario sin entidad no puede solicitar una baja"
                    + " por alcance propio");
        }

        var registration = registrations.byUuid(input.aliasUuid()).orElseThrow(
                () -> PortalException.notFound("No existe un registro con ese AliasUUID"));

        // Un registro de otra entidad se contesta igual que uno inexistente, y a
        // propósito: distinguirlos le diría al agente que ese UUID existe en
        // algún otro banco, que es justo lo que no tiene por qué saber por esta
        // vía. La consulta de alias ya le dice cuáles son suyos.
        if (registration.bankId() != user.bankId()) {
            throw PortalException.notFound("No existe un registro de su entidad con ese AliasUUID");
        }
        if (!"ACTIVO".equals(registration.status())) {
            throw PortalException.conflict("ICG-409-YA-DESACTIVADO",
                    "El registro ya está " + registration.status().toLowerCase());
        }
        if (requests.hasPendingFor(registration.id())) {
            throw PortalException.conflict("ICG-409-SOLICITUD-DUPLICADA",
                    "Ya hay una solicitud de baja pendiente para ese registro");
        }

        var now = Instant.now();
        requests.create(registration.id(), SCOPE_OWN_BANK, user.bankId(), user.id(), reason,
                now, now.plus(requestTtl));

        log.info("portal baja solicitada: usuario={} banco={} registro={}",
                user.username(), user.bic(), registration.id());
    }

    // ---- bandeja ----------------------------------------------------------

    public List<PortalDto.CancellationView> inbox(PortalUser user) {
        if (!user.belongsToBank()) {
            return List.of();
        }
        return views(requests.byBank(user.bankId(), INBOX_LIMIT));
    }

    // ---- aprobación y rechazo --------------------------------------------

    @Transactional
    public void approve(long requestId, PortalUser approver) {
        var request = pendingOf(requestId, approver);
        var now = Instant.now();

        if (request.expiresAt().toInstant().isBefore(now)) {
            throw PortalException.conflict("ICG-409-SOLICITUD-VENCIDA",
                    "La solicitud venció y debe capturarse de nuevo");
        }

        var registration = registrations.byUuid(request.aliasUuid()).orElseThrow(
                () -> PortalException.notFound("El registro de la solicitud ya no existe"));

        if (!"ACTIVO".equals(registration.status())) {
            throw PortalException.conflict("ICG-409-YA-DESACTIVADO",
                    "El registro ya está " + registration.status().toLowerCase()
                    + ": la baja no se aplica");
        }

        // Cierra primero y ejecuta después, dentro de la misma transacción: el
        // UPDATE condicionado a PENDIENTE es lo que resuelve la carrera entre
        // dos aprobadores, y si quedara para el final los dos habrían ejecutado
        // la baja antes de descubrir que sólo uno podía.
        boolean closed = requests.resolve(requestId, "EJECUTADA", approver.id(), null, now, now);
        if (!closed) {
            throw PortalException.conflict("ICG-409-SOLICITUD-RESUELTA",
                    "Otra persona resolvió esta solicitud primero");
        }

        var outcome = deactivator.deactivate(registration, now, SCOPE_OWN_BANK,
                "PORTAL-" + requestId, approver.bankId(),
                AliasDeactivator.Origin.portal(approver.id()));

        log.info("portal baja aprobada: aprobador={} banco={} solicitud={} estado={} cuarentena={}",
                approver.username(), approver.bic(), requestId, outcome.status(),
                outcome.quarantined());
    }

    @Transactional
    public void reject(long requestId, PortalDto.CancellationRejectInput input,
            PortalUser approver) {
        String comment = requireReason(input.reason());
        pendingOf(requestId, approver);

        boolean closed = requests.resolve(requestId, "RECHAZADA", approver.id(), comment,
                Instant.now(), null);
        if (!closed) {
            throw PortalException.conflict("ICG-409-SOLICITUD-RESUELTA",
                    "Otra persona resolvió esta solicitud primero");
        }

        log.info("portal baja rechazada: aprobador={} banco={} solicitud={}",
                approver.username(), approver.bic(), requestId);
    }

    // ---- lo común ---------------------------------------------------------

    private DeactivationRepository.Row pendingOf(long requestId, PortalUser approver) {
        var request = requests.byId(requestId).orElseThrow(
                () -> PortalException.notFound("No existe esa solicitud"));

        if (!approver.belongsToBank() || request.bankId() != approver.bankId()) {
            throw PortalException.notFound("No existe una solicitud de su entidad con ese id");
        }
        if (!"PENDIENTE".equals(request.status())) {
            throw PortalException.conflict("ICG-409-SOLICITUD-RESUELTA",
                    "La solicitud ya está " + request.status().toLowerCase());
        }
        // El motor lo impide igual; esto existe para que el agente lea por qué.
        if (request.requesterId() == approver.id()) {
            throw PortalException.conflict("ICG-409-DOBLE-CONTROL",
                    "Quien captura una baja no puede aprobarla: debe hacerlo otra persona"
                    + " de su entidad");
        }
        return request;
    }

    private static String requireReason(String reason) {
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() < MIN_REASON_LENGTH) {
            throw PortalException.badRequest(
                    "El motivo debe tener al menos " + MIN_REASON_LENGTH + " caracteres");
        }
        if (trimmed.length() > MAX_REASON_LENGTH) {
            throw PortalException.badRequest(
                    "El motivo no puede pasar de " + MAX_REASON_LENGTH + " caracteres");
        }
        return trimmed;
    }

    /**
     * Descifra los alias de la bandeja en UNA llamada al KMS y los enmascara.
     *
     * <p>Sale enmascarado y no completo: quien aprueba decide sobre un registro
     * y no necesita el teléfono entero para hacerlo (regla T-8).
     */
    private List<PortalDto.CancellationView> views(List<DeactivationRepository.Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> aliases = kms.decrypt(encryptionKey,
                rows.stream().map(DeactivationRepository.Row::aliasEnc).toList());

        var views = new ArrayList<PortalDto.CancellationView>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            views.add(new PortalDto.CancellationView(
                    row.id(), row.aliasUuid(), AliasMask.of(aliases.get(i)), row.bic(),
                    row.bankName(), RequestStatus.of(row.status()), row.reason(),
                    row.requestedBy(),
                    DATE.format(row.requestedAt().toInstant()), row.resolvedBy(),
                    row.resolvedAt() == null ? null : DATE.format(row.resolvedAt().toInstant()),
                    row.approverComment()));
        }
        return views;
    }
}
