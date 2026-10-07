package com.icg.aliasdirectory.mainapi.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
// Jackson 3, no 2: Spring Boot 4 trae tools.jackson y el viejo
// com.fasterxml.jackson ya no esta en el classpath.
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Escribe la bitácora del portal (Anexo §8.4) y la deja consultar.
 *
 * <p><b>Falla cerrado.</b> Si la línea de bitácora no se puede escribir, la
 * operación falla. Es deliberado y discutible: una consulta al padrón que no
 * deja rastro es peor, en una auditoría bancaria, que una consulta que no se
 * pudo hacer. El RFP §4.1 pide registro de toda operación, no registro cuando
 * se pueda.
 *
 * <p>La escritura va en su propia transacción ({@code REQUIRES_NEW}) para que un
 * rechazo o un error posterior de la operación no se lleve por delante el
 * registro de que se intentó. Una bitácora que sólo guarda lo que salió bien no
 * sirve para investigar nada.
 */
@ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "true")
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Última red de seguridad contra la regla T-8.
     *
     * <p>Busca en los valores de {@code detail} lo que tiene forma de dato
     * sensible sin enmascarar: una ristra de ocho o más dígitos (cuenta, DPI,
     * teléfono con prefijo) o un IBAN. No pretende ser exhaustivo —un control de
     * forma nunca lo es— pero sí atrapa el descuido típico de pasar el valor
     * completo donde iba el enmascarado.
     */
    private static final Pattern LOOKS_SENSITIVE =
            Pattern.compile("\\d{8,}|[A-Z]{2}\\d{2}[A-Z0-9]{10,}");

    private static final int MAX_RESULTS = 500;

    private final AuditRepository repository;
    // Igual que TransitClient: el mapper es inmutable y seguro entre hilos, asi
    // que uno por instancia alcanza y no hay que inyectar el del contexto.
    private final JsonMapper json = JsonMapper.builder().build();

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditEntry entry, PortalUser user, HttpServletRequest request) {
        String detailJson = serialize(entry.detail());
        var now = LocalDateTime.now();

        repository.write(new AuditRepository.AuditRow(
                now,
                user == null ? null : user.id(),
                user == null ? "ANONIMO" : user.username(),
                user == null ? null : user.bankId(),
                user == null ? null : user.bic(),
                clientIp(request),
                truncate(request == null ? null : request.getHeader("User-Agent"), 255),
                entry.operation(),
                entry.entity(),
                truncate(entry.entityRef(), 64),
                entry.result(),
                entry.httpStatus(),
                detailJson,
                UUID.randomUUID().toString()));
    }

    /**
     * Consulta de la bitácora con los filtros de PTRAD-787.
     *
     * <p>El alcance no se negocia desde el cliente: quien sólo tiene
     * BITACORA_CONSULTAR ve su entidad y nada más, aunque mande otro BIC. La
     * consulta misma se registra, porque leer quién consultó qué también es una
     * operación sobre datos de personas.
     */
    public List<PortalDto.AuditView> search(PortalDto.AuditFilter filter, PortalUser user,
            HttpServletRequest request) {
        boolean global = user.can(PortalPermission.AUDIT_READ_GLOBAL);
        if (!global && !user.can(PortalPermission.AUDIT_READ)) {
            throw new CurrentPortalUser.MissingPermissionException(PortalPermission.AUDIT_READ);
        }
        Integer scope = global ? null : user.bankId();
        if (!global && scope == null) {
            // Un usuario de ICG sin el permiso global no tiene entidad propia
            // que consultar: no hay alcance que aplicarle.
            throw new CurrentPortalUser.MissingPermissionException(
                    PortalPermission.AUDIT_READ_GLOBAL);
        }

        LocalDate to = filter.to() == null ? LocalDate.now() : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(7) : filter.from();
        if (from.isAfter(to)) {
            throw new PortalException(400, "ICG-400-RANGO-INVALIDO",
                    "La fecha inicial es posterior a la final.");
        }

        int limit = filter.limit() == null || filter.limit() <= 0
                ? 100 : Math.min(filter.limit(), MAX_RESULTS);

        var rows = repository.search(from, to, scope, filter.userLogin(), filter.operation(),
                limit);

        record(AuditEntry.ok(AuditOperation.AUDIT_QUERY, "BITACORA", null,
                Map.of("desde", from.toString(), "hasta", to.toString(),
                        "filas", String.valueOf(rows.size()))), user, request);
        return rows;
    }

    public List<String> operations(PortalDto.AuditFilter filter, PortalUser user) {
        boolean global = user.can(PortalPermission.AUDIT_READ_GLOBAL);
        if (!global && !user.can(PortalPermission.AUDIT_READ)) {
            throw new CurrentPortalUser.MissingPermissionException(PortalPermission.AUDIT_READ);
        }
        LocalDate to = filter.to() == null ? LocalDate.now() : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(30) : filter.from();
        return repository.distinctOperations(from, to, global ? null : user.bankId());
    }

    private String serialize(Map<String, String> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        detail.forEach((key, value) -> {
            if (value != null && LOOKS_SENSITIVE.matcher(value).find()) {
                // No se registra una versión recortada ni se deja pasar: el
                // llamador tiene que enmascarar en origen.
                throw new IllegalStateException(
                        "La bitácora rechazó el campo '" + key + "': parece un dato sin "
                                + "enmascarar (regla T-8)");
            }
        });
        try {
            return json.writeValueAsString(detail);
        } catch (JacksonException e) {
            log.warn("bitacora: detalle no serializable, se guarda sin él");
            return null;
        }
    }

    /**
     * IP del cliente.
     *
     * <p>Se prefiere {@code X-Forwarded-For} porque en producción el portal
     * queda detrás de un ingress y {@code getRemoteAddr} devolvería siempre la
     * del proxy. Se toma el primer salto, que es el cliente original, y se
     * valida que tenga forma de IP: la cabecera la pone el cliente y
     * {@code INET6_ATON} devolvería NULL ante cualquier texto, lo que violaría
     * el NOT NULL de la columna.
     */
    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "0.0.0.0";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (isIpLike(first)) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return isIpLike(remote) ? remote : "0.0.0.0";
    }

    private boolean isIpLike(String value) {
        return value != null && value.length() <= 45
                && value.matches("[0-9a-fA-F:.]+");
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
