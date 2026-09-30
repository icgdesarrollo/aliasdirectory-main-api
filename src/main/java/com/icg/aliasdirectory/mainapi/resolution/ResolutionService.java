package com.icg.aliasdirectory.mainapi.resolution;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Reason;
import com.icg.aliasdirectory.mainapi.availability.RegistrationAvailabilityService;
import com.icg.aliasdirectory.mainapi.integrity.RegistryVerifier;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;
import com.icg.aliasdirectory.messaging.error.ResponseCode;
import com.icg.aliasdirectory.messaging.extensions.Extension;
import com.icg.aliasdirectory.messaging.extensions.Extensions;
import com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationVerification5;
import com.icg.aliasdirectory.messaging.iso.acmt024.AccountIdentification4Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.BranchAndFinancialInstitutionIdentification8;
import com.icg.aliasdirectory.messaging.iso.acmt024.CashAccount40;
import com.icg.aliasdirectory.messaging.iso.acmt024.CashAccountType2Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.Document;
import com.icg.aliasdirectory.messaging.iso.acmt024.FinancialInstitutionIdentification23;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationAssignment4;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationInformation5;
import com.icg.aliasdirectory.messaging.iso.acmt024.IdentificationVerificationReportV04;
import com.icg.aliasdirectory.messaging.iso.acmt024.MessageIdentification8;
import com.icg.aliasdirectory.messaging.iso.acmt024.ObjectFactory;
import com.icg.aliasdirectory.messaging.iso.acmt024.Party50Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.SupplementaryData1;
import com.icg.aliasdirectory.messaging.iso.acmt024.SupplementaryDataEnvelope1;
import com.icg.aliasdirectory.messaging.iso.acmt024.VerificationReason1Choice;
import com.icg.aliasdirectory.messaging.iso.acmt024.VerificationReport5;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * F3 · Resolución de alias (Anexo §4).
 *
 * <p>Es la operación que ejecuta una transferencia: el banco manda un teléfono y
 * recibe las cuentas a las que resuelve, una por cada entidad donde el alias
 * está vigente. A diferencia de Disponibilidad Resolución, que sólo dice si vale
 * la pena intentarlo, aquí sí viajan IBAN, tipo y moneda descifrados.
 *
 * <p>Tres diferencias con su prima la disponibilidad, y ninguna es cosmética:
 *
 * <ol>
 *   <li>El {@code OrgtrCustId} es <b>obligatorio</b>. Sin saber qué cliente
 *       originó la consulta no hay a quién atribuir la resolución, y toda
 *       resolución se audita.</li>
 *   <li>La respuesta lleva un {@code EvtAlias} obligatorio: el identificador de
 *       esta resolución, con el que se rastrea después.</li>
 *   <li>Descifra, así que necesita KMS. Por eso el servicio no existe cuando el
 *       KMS está apagado, igual que el registro y la consulta por cliente.</li>
 * </ol>
 *
 * <p><b>Un alias por petición.</b> El esquema de ISO admite varios
 * {@code Vrfctn} en la solicitud y esta operación los rechaza: el código HTTP es
 * uno por respuesta —200, 404 o 409 según el §6— y con un lote de alias, unos
 * resueltos y otros no, no hay código que diga la verdad sobre todos. Es el
 * hallazgo H-36, todavía sin decidir con funcional; mientras tanto se rechaza
 * explícitamente en vez de contestar algo ambiguo.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Service
public class ResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionService.class);

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final ResolutionRepository registry;
    private final TransitClient kms;
    private final Optional<RegistryVerifier> verifier;
    private final String encryptionKey;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    public ResolutionService(MessageSerializer serializer, BlindIndex index,
            ResolutionRepository registry, TransitClient kms,
            Optional<RegistryVerifier> verifier,
            @Value("${icg.kms.encryption-key}") String encryptionKey,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.index = index;
        this.registry = registry;
        this.kms = kms;
        this.verifier = verifier;
        this.encryptionKey = encryptionKey;
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** El acmt.024 y el código con el que debe viajar, decididos juntos. */
    public record Response(byte[] body, int httpStatus) {
    }

    public Response handle(byte[] requestBody, String requestingBic) {
        var request = serializer.parse(requestBody,
                com.icg.aliasdirectory.messaging.iso.acmt023.Document.class).getIdVrfctnReq();

        List<IdentificationVerification5> queries = request.getVrfctn();
        if (queries.size() != 1) {
            throw new InvalidResolutionException("La resolución atiende un alias por"
                    + " petición y esta trae " + queries.size() + " (hallazgo H-36)");
        }
        IdentificationVerification5 query = queries.get(0);

        // El OrgtrCustId se exige aquí y no se confía en que el XSD lo haya
        // atrapado: el perfil lo declara obligatorio, pero este servicio también
        // se puede invocar desde una prueba o desde otro canal, y una resolución
        // sin cliente originador es una resolución que no se puede auditar.
        String originatorCustomerId = originatorOf(request);

        String alias = query.getPtyAndAcctId().getAcct().getPrxy().getId();
        byte[] aliasBidx = index.ofAlias(alias);

        // H-57: comprobar que los registros de este alias no fueron alterados por
        // fuera de la aplicación ANTES de responder con ellos. Aquí importa más
        // que en ninguna otra operación: lo que sale de aquí es el destino de una
        // transferencia de dinero.
        verifier.ifPresent(v -> v.verify(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx));

        var rows = registry.registrationsOfAlias(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx);
        var active = rows.stream().filter(ResolutionRepository.EncryptedRow::active).toList();

        String evtAlias = UUID.randomUUID().toString();

        if (rows.isEmpty()) {
            // No existe. Sin motivo: los motivos explican por qué un alias que
            // existe no se puede usar, no la ausencia.
            return report(request, requestingBic, query, evtAlias, List.of(), null,
                    ResponseCode.RESOLUTION_NOT_FOUND);
        }
        if (active.isEmpty()) {
            // Existe y no es usable: todos sus registros están en cuarentena.
            return report(request, requestingBic, query, evtAlias, List.of(), Reason.QUARANTINE,
                    ResponseCode.RESOLUTION_BLOCKED);
        }
        return report(request, requestingBic, query, evtAlias, decrypt(active), null,
                ResponseCode.RESOLUTION_OK);
    }

    /**
     * Descifra IBAN, tipo y moneda de todas las filas en UNA llamada al KMS.
     *
     * <p>Tres viajes por banco serían nueve para un alias registrado en tres
     * entidades, sobre una operación con SLA de 100 ms. Medido en E04-D02: 0.99
     * ms por identificador en lote contra 4.84 ms suelto.
     *
     * <p>El alias NO se descifra: el banco ya sabe cuál preguntó, y devolverlo
     * obligaría a traerlo del padrón sin necesidad.
     */
    private List<Resolved> decrypt(List<ResolutionRepository.EncryptedRow> rows) {
        var ciphertexts = new ArrayList<String>(rows.size() * 3);
        for (var row : rows) {
            ciphertexts.add(row.ibanEnc());
            ciphertexts.add(row.accountTypeEnc());
            ciphertexts.add(row.currencyEnc());
        }
        List<String> plain = kms.decrypt(encryptionKey, ciphertexts);

        var resolved = new ArrayList<Resolved>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            int base = i * 3;
            resolved.add(new Resolved(plain.get(base), plain.get(base + 1),
                    plain.get(base + 2), rows.get(i).bic()));
        }
        return resolved;
    }

    private Response report(
            com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationVerificationRequestV04 request,
            String requestingBic, IdentificationVerification5 query, String evtAlias,
            List<Resolved> resolved, Reason reason, ResponseCode code) {

        var report = new IdentificationVerificationReportV04();
        report.setAssgnmt(responseTo(requestingBic));
        report.setOrgnlAssgnmt(original(request.getAssgnmt()));

        if (resolved.isEmpty()) {
            report.getRpt().add(verification(query.getId(), false, reason, null));
        } else {
            // Un Rpt por banco, todos con el mismo OrgnlId: corresponden a la
            // misma solicitud y se distinguen por el Agt de cada cuenta.
            for (Resolved r : resolved) {
                report.getRpt().add(verification(query.getId(), true, null, r));
            }
        }
        report.getSplmtryData().add(evtAliasEnvelope(evtAlias));

        // El EvtAlias y el banco sí; el alias, el IdCliente y las cuentas NUNCA
        // (regla T-8). Con el EvtAlias se rastrea todo lo demás en la bitácora,
        // que es donde esos datos sí pueden estar.
        log.info("resolucion: banco={} evtAlias={} http={} registros={}",
                requestingBic, evtAlias, code.httpStatus(), resolved.size());

        var out = new Document();
        out.setIdVrfctnRpt(report);
        return new Response(serializer.build(new ObjectFactory().createDocument(out)),
                code.httpStatus());
    }

    private static VerificationReport5 verification(String originalId, boolean vrfctn,
            Reason reason, Resolved resolved) {
        var rpt = new VerificationReport5();
        rpt.setOrgnlId(originalId);
        rpt.setVrfctn(vrfctn);
        if (reason != null) {
            var rsn = new VerificationReason1Choice();
            rsn.setPrtry(reason.name());
            rpt.setRsn(rsn);
        }
        if (resolved != null) {
            rpt.setUpdtdPtyAndAcctId(accountOf(resolved));
        }
        return rpt;
    }

    /**
     * La cuenta a la que resuelve, con el banco que la tiene.
     *
     * <p>Sin {@code Pty}: el perfil no lo admite y el Anexo es explícito en que
     * el banco consultante no recibe datos del titular más allá de los listados.
     */
    private static IdentificationInformation5 accountOf(Resolved r) {
        var iban = new AccountIdentification4Choice();
        iban.setIBAN(r.iban());
        var type = new CashAccountType2Choice();
        type.setCd(r.accountType());

        var account = new CashAccount40();
        account.setId(iban);
        account.setTp(type);
        account.setCcy(r.currency());

        var info = new IdentificationInformation5();
        info.setAcct(account);
        info.setAgt(agent(r.bic()));
        return info;
    }

    /** El EvtAlias en SupplementaryData, que el perfil de F3 exige. */
    private static SupplementaryData1 evtAliasEnvelope(String evtAlias) {
        var envelope = new SupplementaryDataEnvelope1();
        envelope.setAny(new com.icg.aliasdirectory.messaging.icg.ext.ObjectFactory()
                .createEvtAlias(evtAlias));
        var data = new SupplementaryData1();
        data.setEnvlp(envelope);
        return data;
    }

    /**
     * El IdCliente que origina la transferencia, de SupplementaryData.
     *
     * <p>Viene cifrado con la llave pública de ICG, y aquí no se descifra: el
     * único consumidor es la bitácora, que es asíncrona. Se lee para comprobar
     * que está —una resolución sin él no se puede atribuir a nadie— y se pasa
     * tal cual.
     */
    private static String originatorOf(
            com.icg.aliasdirectory.messaging.iso.acmt023.IdentificationVerificationRequestV04 request) {
        var envelopes = request.getSplmtryData().stream()
                .map(d -> d.getEnvlp() == null ? null : d.getEnvlp().getAny())
                .toList();
        return Extensions.read(envelopes).text(Extension.ORGTR_CUST_ID)
                .orElseThrow(() -> new InvalidResolutionException(
                        "La resolución exige OrgtrCustId en SplmtryData (icg.ext.002):"
                        + " sin cliente originador la operación no se puede auditar"));
    }

    private IdentificationAssignment4 responseTo(String requestingBic) {
        var a = new IdentificationAssignment4();
        a.setMsgId("RSLRPT-" + STAMP.format(Instant.now()));
        a.setCreDtTm(now());
        a.setAssgnr(party(directoryBic));
        // El destinatario es el banco que selló Dispatch, no el Assgnr del cuerpo.
        a.setAssgne(party(requestingBic));
        return a;
    }

    private static MessageIdentification8 original(
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

    /** Una cuenta ya descifrada, lista para volverse UpdtdPtyAndAcctId. */
    private record Resolved(String iban, String accountType, String currency, String bic) {
    }

    /** La solicitud pasó el XSD pero no sirve para resolver. */
    public static class InvalidResolutionException extends RuntimeException {
        public InvalidResolutionException(String message) {
            super(message);
        }
    }
}
