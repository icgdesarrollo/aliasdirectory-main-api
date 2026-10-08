package com.icg.aliasdirectory.mainapi.availability;

import com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationVerification5;
import com.icg.aliasdirectory.messaging.iso.acmt024.BranchAndFinancialInstitutionIdentification8;
import com.icg.aliasdirectory.messaging.iso.acmt024.CashAccount40;
import com.icg.aliasdirectory.messaging.iso.acmt024.Document;
import com.icg.aliasdirectory.messaging.iso.acmt024.FinancialInstitutionIdentification23;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationAssignment4;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationInformation5;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationVerificationReportV04;
import com.icg.aliasdirectory.messaging.iso.acmt024.MessageIdentification8;
import com.icg.aliasdirectory.messaging.iso.acmt024.ObjectFactory;
import com.icg.aliasdirectory.messaging.iso.acmt024.Party50Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.ProxyAccountIdentification1;
import com.icg.aliasdirectory.messaging.iso.acmt024.ProxyAccountType1Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.VerificationReason1Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.VerificationReport5;
import com.icg.aliasdirectory.mainapi.audit.ResolutionAuditEvent;
import com.icg.aliasdirectory.mainapi.audit.ResolutionAuditPublisher;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.UUID;

/**
 * F1 · Disponibilidad de registro (Anexo §4).
 *
 * <p>El banco pregunta si uno o varios alias están en uso antes de ofrecérselos
 * al cliente. Una solicitud puede traer varios {@code Vrfctn} y la respuesta
 * lleva un {@code Rpt} por cada uno, correlacionados por {@code Id}.
 *
 * <p>El BIC del banco consultante viene SELLADO por Dispatch en una propiedad
 * AMQP, no del {@code Assgnr} del mensaje. Detrás de la cola ya no hay banco al
 * otro lado del socket: el consumidor no puede volver a validar la identidad, y
 * el {@code Assgnr} lo escribe el propio banco y podría decir cualquier cosa.
 * Confiar en el cuerpo aquí sería dejar que un banco consultara como otro.
 */
@Service
public class RegistrationAvailabilityService {

    private static final Logger log =
            LoggerFactory.getLogger(RegistrationAvailabilityService.class);

    /**
     * v1.0 sólo soporta alias telefónico, que viaja como SHID (H-01).
     *
     * <p>Público porque la resolución, la baja y el portal viven en otros
     * paquetes y todos tienen que preguntar por el mismo tipo: si cada uno
     * escribiera el literal, un cambio de tipo dejaría consultas buscando por
     * un valor que ya nadie escribe, y devolverían «no existe» en vez de fallar.
     */
    public static final String ALIAS_TYPE = "SHID";

    private static final DateTimeFormatter SELLO =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final RegistryRepository registry;
    private final java.util.Optional<ResolutionAuditPublisher> audit;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    public RegistrationAvailabilityService(MessageSerializer serializer, BlindIndex index,
            RegistryRepository registry,
            java.util.Optional<ResolutionAuditPublisher> audit,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.index = index;
        this.registry = registry;
        this.audit = audit;
        // Deja constancia en el arranque de si esta instancia audita o no. Sin
        // esto, un publicador ausente no se nota: las consultas responden igual
        // y la bitacora simplemente no crece, que es el peor modo de fallar.
        log.info("auditoria de consultas: {}",
                audit.map(Object::toString).orElse("DESACTIVADA (sin publicador)"));
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** @return la acmt.024 lista para devolverle al banco */
    public byte[] handle(byte[] requestBody, String requestingBic, String amqpsQueue) {
        var doc = serializer.parse(requestBody,
                com.icg.aliasdirectory.messaging.iso.acmt023.Document.class);
        var request = doc.getIdVrfctnReq();

        var report = new IdentificationVerificationReportV04();
        report.setAssgnmt(responseTo(request.getAssgnmt(), requestingBic));
        report.setOrgnlAssgnmt(original(request.getAssgnmt()));

        // Un evento de auditoría por cada Vrfctn, no uno por mensaje: la bitácora
        // es por alias consultado, y este perfil admite varios en una llamada.
        String msgId = request.getAssgnmt().getMsgId();
        for (IdentificationVerification5 query : request.getVrfctn()) {
            report.getRpt().add(resolve(query, requestingBic, amqpsQueue, msgId));
        }

        var out = new Document();
        out.setIdVrfctnRpt(report);
        return serializer.build(new ObjectFactory().createDocument(out));
    }

    private VerificationReport5 resolve(IdentificationVerification5 query,
            String requestingBic, String amqpsQueue, String msgId) {
        long inicio = System.nanoTime();
        String alias = query.getPtyAndAcctId().getAcct().getPrxy().getId();
        String dpi = dpiOf(query);

        byte[] aliasBidx = index.ofAlias(alias);

        var registrations = registry.activeRegistrations(
                ALIAS_TYPE, aliasBidx, index.ofDpi(dpi));
        var decision = AvailabilityRule.decide(registrations, requestingBic);

        // Se registra el resultado, NUNCA el alias ni el DPI (regla T-8). El Id
        // de correlación sí: lo eligió el banco y no identifica a nadie.
        log.info("disponibilidad: banco={} consulta={} vrfctn={} motivo={} registros={}",
                requestingBic, query.getId(), decision.vrfctn(),
                decision.reason().map(Enum::name).orElse("-"), registrations.size());

        var rpt = new VerificationReport5();
        rpt.setOrgnlId(query.getId());
        rpt.setVrfctn(decision.vrfctn());
        decision.reason().ifPresent(m -> {
            var rsn = new VerificationReason1Choice();
            rsn.setPrtry(m.name());
            rpt.setRsn(rsn);
        });
        // Sólo se devuelve en qué entidad está el alias cuando el motivo es el
        // conflicto con otro banco: ahí el banco consultante necesita saber a
        // quién referir al cliente. En los demás casos no se dice, porque sería
        // contarle a un banco dónde tiene cuentas una persona que no es su
        // cliente. El perfil además impide devolver datos del titular.
        decision.reasonBic().ifPresent(bic -> rpt.setUpdtdPtyAndAcctId(aliasOnly(
                query.getPtyAndAcctId().getAcct().getPrxy().getId(), bic)));

        // La auditoría va al final y FUERA del camino de la respuesta: si el KMS
        // o el broker fallan, el publicador lo registra y la consulta responde
        // igual. El EvtAlias se genera aquí aunque este perfil no lo devuelva:
        // es la única forma de cruzar este evento con el log de acceso.
        //
        // No hay cliente originador —este perfil ni siquiera lo declara— y no hay
        // detalle: disponibilidad no devuelve cuentas, sólo si el alias se puede
        // usar. returned_records es 1 cuando se dijo en qué entidad está, 0 si no.
        if (audit.isPresent()) {
            audit.get().publish(UUID.randomUUID().toString(),
                    ResolutionAuditEvent.DISP_REGISTRO, alias, aliasBidx, null,
                    requestingBic, amqpsQueue, msgId, null, decision.vrfctn(),
                    decision.reason().map(Enum::name).orElse(null), 200,
                    decision.reasonBic().isPresent() ? 1 : 0,
                    (System.nanoTime() - inicio) / 1_000_000L, null, List.of());
        }

        return rpt;
    }

    /** El DPI del titular: el {@code Othr} con {@code SchmeNm.Cd = NIDN}. */
    private static String dpiOf(IdentificationVerification5 query) {
        List<com.icg.aliasdirectory.messaging.iso.acmt023.GenericPersonIdentification2> otros =
                query.getPtyAndAcctId().getPty().getId().getPrvtId().getOthr();
        return otros.stream()
                .filter(o -> o.getSchmeNm() != null && "NIDN".equals(o.getSchmeNm().getCd()))
                .map(o -> o.getId())
                .findFirst()
                .orElseThrow(() -> new RequestWithoutDpiException(query.getId()));
    }

    private IdentificationInformation5 aliasOnly(String alias, String bic) {
        var tipo = new ProxyAccountType1Choice();
        tipo.setCd(ALIAS_TYPE);
        var prxy = new ProxyAccountIdentification1();
        prxy.setTp(tipo);
        prxy.setId(alias);
        var account = new CashAccount40();
        account.setPrxy(prxy);

        var info = new IdentificationInformation5();
        info.setAcct(account);
        info.setAgt(agent(bic));
        return info;
    }

    private IdentificationAssignment4 responseTo(
            com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationAssignment4 original,
            String requestingBic) {
        var a = new IdentificationAssignment4();
        a.setMsgId("DRGRPT-" + SELLO.format(Instant.now()));
        a.setCreDtTm(now());
        // Se invierten: quien responde es el directorio, el destinatario es el
        // banco que Dispatch selló, no el Assgnr que venía en el cuerpo.
        a.setAssgnr(party(directoryBic));
        a.setAssgne(party(requestingBic));
        return a;
    }

    private MessageIdentification8 original(
            com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationAssignment4 original) {
        var m = new MessageIdentification8();
        m.setMsgId(original.getMsgId());
        m.setCreDtTm(original.getCreDtTm());
        return m;
    }

    private static Party50Choice party(String bic) {
        var p = new Party50Choice();
        p.setAgt(agent(bic));
        return p;
    }

    private static BranchAndFinancialInstitutionIdentification8 agent(String bic) {
        var fi = new FinancialInstitutionIdentification23();
        fi.setBICFI(bic);
        var agt = new BranchAndFinancialInstitutionIdentification8();
        agt.setFinInstnId(fi);
        return agt;
    }

    private javax.xml.datatype.XMLGregorianCalendar now() {
        return datatypeFactory.newXMLGregorianCalendar(
                GregorianCalendar.from(ZonedDateTime.now(ZoneOffset.UTC)));
    }

    /** El perfil exige Pty, pero no que uno de sus Othr sea el DPI. */
    public static class RequestWithoutDpiException extends RuntimeException {
        public RequestWithoutDpiException(String idConsulta) {
            super("La consulta " + idConsulta + " no trae un Othr con SchmeNm.Cd=NIDN");
        }
    }
}
