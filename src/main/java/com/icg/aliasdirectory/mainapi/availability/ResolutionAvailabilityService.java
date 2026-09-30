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
import com.icg.aliasdirectory.mainapi.integrity.RegistryVerifier;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.GregorianCalendar;

/**
 * F1 · Disponibilidad de resolución (Anexo §4).
 *
 * <p>El banco pregunta, antes de una transferencia, si un alias se puede
 * resolver. A diferencia de Disponibilidad Registro, la solicitud NO trae DPI: el
 * perfil ni siquiera declara el elemento {@code Pty}, así que un mensaje que lo
 * lleve se rechaza en el borde y nunca llega aquí.
 *
 * <p>Esa ausencia tiene una consecuencia que vale la pena entender, porque es la
 * razón de que este servicio exista separado del otro y no sea una bandera:
 * sin DPI de entrada no hay con qué comparar el DPI almacenado, y por lo tanto
 * los dos motivos del §3.5 que se definen por esa comparación son
 * <b>incalculables</b>, no sólo indeseables. El único motivo posible es
 * QUARANTINE.
 *
 * <p>La respuesta tampoco lleva IBAN, tipo ni moneda. Eso lo entrega F3, que es
 * la resolución propiamente dicha y se audita como tal. Esta consulta sólo dice
 * si vale la pena intentarla.
 */
@Service
public class ResolutionAvailabilityService {

    private static final Logger log =
            LoggerFactory.getLogger(ResolutionAvailabilityService.class);

    private static final DateTimeFormatter SELLO =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final RegistryRepository registry;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    /**
     * Vacío cuando el KMS está apagado: el sello de integridad no existe sin él.
     *
     * <p>Se inyecta como Optional y no como dependencia dura para que este
     * servicio siga funcionando sin KMS, que es el modo en que arranca una
     * compilación recién clonada. Sin sello no hay verificación, y eso es una
     * degradación consciente, no un olvido.
     */
    private final java.util.Optional<RegistryVerifier> verifier;

    public ResolutionAvailabilityService(MessageSerializer serializer, BlindIndex index,
            RegistryRepository registry,
            java.util.Optional<RegistryVerifier> verifier,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.index = index;
        this.registry = registry;
        this.verifier = verifier;
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    public byte[] handle(byte[] requestBody, String requestingBic) {
        var doc = serializer.parse(requestBody,
                com.icg.aliasdirectory.messaging.iso.acmt023.Document.class);
        var request = doc.getIdVrfctnReq();

        var report = new IdentificationVerificationReportV04();
        report.setAssgnmt(responseTo(requestingBic));
        report.setOrgnlAssgnmt(original(request.getAssgnmt()));

        for (IdentificationVerification5 query : request.getVrfctn()) {
            report.getRpt().add(resolve(query, requestingBic));
        }

        var out = new Document();
        out.setIdVrfctnRpt(report);
        return serializer.build(new ObjectFactory().createDocument(out));
    }

    private VerificationReport5 resolve(IdentificationVerification5 query,
            String requestingBic) {
        String alias = query.getPtyAndAcctId().getAcct().getPrxy().getId();

        byte[] aliasBidx = index.ofAlias(alias);

        // H-57: antes de decir nada sobre este alias, comprobar que sus registros
        // no fueron alterados por fuera de la aplicación. Va ANTES de la consulta
        // que decide la respuesta: responder primero y verificar después sería
        // haber respondido ya con datos que no constan.
        verifier.ifPresent(v -> v.verify(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx));

        var registrations = registry.registrationsOfAlias(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx);
        var decision = ResolvabilityRule.decide(registrations);

        // El resultado sí, el alias NUNCA (regla T-8).
        log.info("resolubilidad: banco={} consulta={} vrfctn={} motivo={} registros={}",
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
        decision.ownerBic().ifPresent(bic -> rpt.setUpdtdPtyAndAcctId(aliasOnly(alias, bic)));
        return rpt;
    }

    /** El alias que preguntaron y el banco que lo tiene. Nada de la cuenta. */
    private IdentificationInformation5 aliasOnly(String alias, String bic) {
        var tipo = new ProxyAccountType1Choice();
        tipo.setCd(RegistrationAvailabilityService.ALIAS_TYPE);
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

    private IdentificationAssignment4 responseTo(String requestingBic) {
        var a = new IdentificationAssignment4();
        a.setMsgId("DRSRPT-" + SELLO.format(Instant.now()));
        a.setCreDtTm(now());
        a.setAssgnr(party(directoryBic));
        // El destinatario es el banco que selló Dispatch, no el Assgnr del cuerpo.
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

    private XMLGregorianCalendar now() {
        return datatypeFactory.newXMLGregorianCalendar(
                GregorianCalendar.from(ZonedDateTime.now(ZoneOffset.UTC)));
    }
}
