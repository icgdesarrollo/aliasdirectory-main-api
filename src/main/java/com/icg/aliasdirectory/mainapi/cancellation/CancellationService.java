package com.icg.aliasdirectory.mainapi.cancellation;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
import com.icg.aliasdirectory.mainapi.availability.RegistrationAvailabilityService;
import com.icg.aliasdirectory.mainapi.registration.RegistryWriteRepository;
import com.icg.aliasdirectory.messaging.error.ResponseCode;
import com.icg.aliasdirectory.messaging.icg.schema.BranchAndFinancialInstitutionIdentification6;
import com.icg.aliasdirectory.messaging.icg.schema.CancellationScope1Code;
import com.icg.aliasdirectory.messaging.icg.schema.CancelledRecord1;
import com.icg.aliasdirectory.messaging.icg.schema.Document;
import com.icg.aliasdirectory.messaging.icg.schema.FinancialInstitutionIdentification18;
import com.icg.aliasdirectory.messaging.icg.schema.IdentificationAssignment3;
import com.icg.aliasdirectory.messaging.icg.schema.ObjectFactory;
import com.icg.aliasdirectory.messaging.icg.schema.OriginalAssignment1;
import com.icg.aliasdirectory.messaging.icg.schema.Party40Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyCancellation1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyCancellationReport1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatus1Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatus1Code;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatusReport1;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * F5 · Desactivar un alias (PrxyCxl → PrxyCxlRpt).
 *
 * <p>Dos alcances, y el Anexo los identifica de forma distinta:
 *
 * <ul>
 *   <li>{@code OWN_BANK} — por {@code AliasUUID}: da de baja el registro del
 *       banco que llama, y sólo ése.</li>
 *   <li>{@code ALL_BANKS} — por {@code Prxy}: da de baja el alias en todas las
 *       entidades donde esté vigente.</li>
 * </ul>
 *
 * <p>Que ALL_BANKS entre por el teléfono y no por el UUID contradice el propio
 * texto del Anexo, que dice que F5 identifica por AliasUUID; son los hallazgos
 * H-22 y H-50, sin resolver. Se implementa como muestran los ejemplos XML, que
 * es lo que el XSD admite y lo que los bancos van a mandar.
 *
 * <p><b>La baja nunca borra.</b> Pasa a INACTIVO, o a BLOQUEADO con fecha de
 * liberación cuando toca cuarentena (ver {@link QuarantineRule}), y el cambio
 * queda en {@code alias_registration_history}.
 *
 * <p><b>Y vuelve a sellar.</b> El estado entra en el sello de integridad, así
 * que una baja que lo cambiara sin recalcularlo dejaría el registro marcado como
 * alterado y la siguiente consulta de ese alias fallaría la verificación de
 * H-57. Todo ocurre en una transacción: o cambia el estado, el histórico y el
 * sello, o no cambia nada.
 */
// El sello de integridad se calcula contra el KMS, y la baja tiene que volver
// a sellar: sin KMS este servicio no puede existir.
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Service
public class CancellationService {

    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final CancellationRepository cancellations;
    private final RegistryWriteRepository registry;
    private final AliasDeactivator deactivator;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    public CancellationService(MessageSerializer serializer, BlindIndex index,
            CancellationRepository cancellations, RegistryWriteRepository registry,
            AliasDeactivator deactivator,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.index = index;
        this.cancellations = cancellations;
        this.registry = registry;
        this.deactivator = deactivator;
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** El PrxyCxlRpt y el código con el que debe viajar, decididos juntos. */
    public record Response(byte[] body, int httpStatus) {
    }

    @Transactional
    public Response handle(byte[] requestBody, String requestingBic) {
        var request = serializer.parse(requestBody, Document.class).getPrxyCxl();
        var scope = request.getCxlScp();
        int bankId = registry.bankId(requestingBic).orElseThrow(
                () -> new IllegalStateException("El BIC " + requestingBic + " no está en"
                        + " participant_bank. El borde lo dejó pasar y no debería."));

        return scope == CancellationScope1Code.ALL_BANKS
                ? cancelEverywhere(request, requestingBic, bankId)
                : cancelOwnBank(request, requestingBic, bankId);
    }

    // ---- OWN_BANK ---------------------------------------------------------

    private Response cancelOwnBank(ProxyCancellation1 request, String requestingBic, int bankId) {
        String aliasUuid = request.getAliasUUID();
        if (aliasUuid == null) {
            throw new InvalidCancellationException(
                    "La baja OWN_BANK identifica el registro por AliasUUID");
        }

        var found = cancellations.byUuid(aliasUuid);

        // Un registro de otra entidad se contesta igual que uno inexistente, y a
        // propósito: distinguirlos le diría al banco que ese UUID existe en algún
        // otro lado, que es justo lo que no tiene por qué saber.
        if (found.isEmpty() || found.get().bankId() != bankId) {
            log.info("baja rechazada: banco={} alcance=OWN_BANK motivo=no-encontrado",
                    requestingBic);
            throw new RegistrationNotFoundException(aliasUuid);
        }
        var registration = found.get();

        if (!"ACTIVO".equals(registration.status())) {
            // Ya estaba deshabilitado: BLOQUEADO o INACTIVO. Es el 409 del §6.
            log.info("baja rechazada: banco={} alcance=OWN_BANK estado={}",
                    requestingBic, registration.status());
            return new Response(
                    report(request, requestingBic, aliasUuid, null, List.of(), false),
                    ResponseCode.CANCELLATION_CONFLICT.httpStatus());
        }

        var now = Instant.now();
        var outcome = apply(registration, now, "OWN_BANK", request.getAssgnmt().getMsgId(), bankId);

        log.info("baja aceptada: banco={} alcance=OWN_BANK regnId={} estado={} cuarentena={}",
                requestingBic, registration.regnId(), outcome.status(), outcome.quarantined());

        return new Response(
                report(request, requestingBic, aliasUuid, now, List.of(), true),
                ResponseCode.CANCELLATION_OK.httpStatus());
    }

    // ---- ALL_BANKS --------------------------------------------------------

    private Response cancelEverywhere(ProxyCancellation1 request, String requestingBic,
            int bankId) {
        if (request.getPrxy() == null) {
            throw new InvalidCancellationException(
                    "La baja ALL_BANKS identifica el alias por Prxy (hallazgos H-22 y H-50)");
        }
        String alias = Normalizer.phone(request.getPrxy().getId());
        byte[] aliasBidx = index.ofAlias(alias);

        var registrations = cancellations.activeOfAlias(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx);

        var cancellable = registrations.stream()
                .filter(r -> "ACTIVO".equals(r.status()))
                .toList();

        if (cancellable.isEmpty()) {
            // O el alias no existe, o todos sus registros ya estaban
            // deshabilitados. Las dos son 409 y no 404: a diferencia de OWN_BANK,
            // aquí el banco no nombró un registro concreto, así que no hay nada
            // «no encontrado» que reportarle.
            log.info("baja rechazada: banco={} alcance=ALL_BANKS vigentes=0", requestingBic);
            return new Response(
                    report(request, requestingBic, null, null, List.of(), false),
                    ResponseCode.CANCELLATION_CONFLICT.httpStatus());
        }

        var now = Instant.now();
        String msgId = request.getAssgnmt().getMsgId();
        for (var registration : cancellable) {
            apply(registration, now, "ALL_BANKS", msgId, bankId);
        }

        log.info("baja aceptada: banco={} alcance=ALL_BANKS registros={}",
                requestingBic, cancellable.size());

        return new Response(
                report(request, requestingBic, null, now, cancellable, true),
                ResponseCode.CANCELLATION_OK.httpStatus());
    }

    // ---- lo común ---------------------------------------------------------

    /**
     * Delega en {@link AliasDeactivator}, que es el mismo camino que recorre una
     * baja aprobada desde el portal: los tres pasos —estado, histórico y
     * resello— tienen que ser idénticos vengan de donde vengan.
     */
    private QuarantineRule.Outcome apply(CancellationRepository.Registration registration,
            Instant now, String scope, String msgId, int originBankId) {
        return deactivator.deactivate(registration, now, scope, msgId, originBankId,
                AliasDeactivator.Origin.api());
    }

    private byte[] report(ProxyCancellation1 request, String requestingBic, String aliasUuid,
            Instant cancelledAt, List<CancellationRepository.Registration> cancelled,
            boolean vrfctn) {

        var report = new ProxyCancellationReport1();
        report.setAssgnmt(responseTo(request.getAssgnmt(), requestingBic));
        report.setOrgnlAssgnmt(original(request.getAssgnmt()));
        report.setCxlScp(request.getCxlScp());
        if (aliasUuid != null) {
            report.setAliasUUID(aliasUuid);
        }
        if (request.getPrxy() != null) {
            report.setPrxy(request.getPrxy());
        }

        if (request.getCxlScp() == CancellationScope1Code.ALL_BANKS) {
            report.setVrfctn(vrfctn);
            for (var registration : cancelled) {
                var record = new CancelledRecord1();
                record.setAliasUUID(registration.aliasUuid());
                record.setSvcr(agentOf(registration.bic()));
                report.getCxldRcrd().add(record);
            }
        } else {
            var status = new ProxyStatus1Choice();
            // CANC: baja aplicada. Es el tercer valor de la enumeracion, junto
            // a ACTV y RJCT que usa el alta.
            status.setCd(ProxyStatus1Code.CANC);
            var statusReport = new ProxyStatusReport1();
            statusReport.setSts(status);
            if (cancelledAt != null) {
                statusReport.setCxlDtTm(toXmlCalendar(cancelledAt));
            }
            report.setStsRpt(statusReport);
        }

        var out = new Document();
        out.setPrxyCxlRpt(report);
        return serializer.build(new ObjectFactory().createDocument(out));
    }

    /** Cabecera de la respuesta, con Assgnr y Assgne invertidos (regla T-4). */
    private IdentificationAssignment3 responseTo(IdentificationAssignment3 original,
            String requestingBic) {
        var assignment = new IdentificationAssignment3();
        assignment.setMsgId("PRXRPT-" + STAMP.format(Instant.now()));
        assignment.setCreDtTm(toXmlCalendar(Instant.now()));
        assignment.setAssgnr(agent(directoryBic));
        assignment.setAssgne(original.getAssgnr());
        return assignment;
    }

    private static OriginalAssignment1 original(IdentificationAssignment3 original) {
        var o = new OriginalAssignment1();
        o.setMsgId(original.getMsgId());
        o.setCreDtTm(original.getCreDtTm());
        return o;
    }

    private static Party40Choice agent(String bic) {
        var party = new Party40Choice();
        party.setAgt(agentOf(bic));
        return party;
    }

    private static BranchAndFinancialInstitutionIdentification6 agentOf(String bic) {
        var institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic);
        var agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }

    private XMLGregorianCalendar toXmlCalendar(Instant instant) {
        return datatypeFactory.newXMLGregorianCalendar(
                GregorianCalendar.from(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)));
    }

    /** La solicitud pasó el XSD pero no trae lo que su alcance necesita. */
    public static class InvalidCancellationException extends RuntimeException {
        public InvalidCancellationException(String message) {
            super(message);
        }
    }

    /** No hay registro de este banco con ese AliasUUID. */
    public static class RegistrationNotFoundException extends RuntimeException {
        public RegistrationNotFoundException(String aliasUuid) {
            super("No existe un registro de este banco con AliasUUID " + aliasUuid);
        }
    }
}
