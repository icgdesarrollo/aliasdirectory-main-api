package com.icg.aliasdirectory.mainapi.uuidquery;

import com.icg.aliasdirectory.mainapi.kms.TransitClient;
import com.icg.aliasdirectory.messaging.error.ResponseCode;
import com.icg.aliasdirectory.messaging.icg.ext.NameDisplayLevel1Code;
import com.icg.aliasdirectory.messaging.icg.schema.AccountIdentification4Choice;
import com.icg.aliasdirectory.messaging.icg.schema.BranchAndFinancialInstitutionIdentification6;
import com.icg.aliasdirectory.messaging.icg.schema.CashAccount40;
import com.icg.aliasdirectory.messaging.icg.schema.CashAccountType2Choice;
import com.icg.aliasdirectory.messaging.icg.schema.Document;
import com.icg.aliasdirectory.messaging.icg.schema.FinancialInstitutionIdentification18;
import com.icg.aliasdirectory.messaging.icg.schema.ObjectFactory;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyAccountIdentification1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyAccountType1Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyQuery1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyRecord1;
import com.icg.aliasdirectory.messaging.icg.schema.SupplementaryData1;
import com.icg.aliasdirectory.messaging.icg.schema.SupplementaryDataEnvelope1;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.GregorianCalendar;
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * F7 · Consultar un registro por su AliasUUID (PrxyQry → PrxyRcrd).
 *
 * <p>Devuelve UN registro identificado por su UUID, con la cuenta descifrada y
 * el nivel de visibilidad del nombre. Es la consulta que usa el portal para
 * abrir el detalle de un alias que ya listó, y la que usa soporte cuando alguien
 * reporta un problema con un registro concreto.
 *
 * <p><b>No devuelve Ownr.</b> Ni DPI ni nombre del titular, por diseño de
 * privacidad: el ejemplo del Anexo lo dice explícitamente y el XSD no los
 * admite. Lo que F7 añade sobre F4 es el estado del registro y su fecha, no
 * datos de la persona.
 *
 * <p><b>Dónde se diferencia de F4.</b> F4 lista los alias operativos de un
 * cliente dentro de su banco y por eso filtra por ACTIVO; F7 responde por un
 * registro identificado y no filtra, porque un registro bloqueado o dado de baja
 * sigue existiendo y es justo lo que quien pregunta está averiguando. El estado
 * va en {@code Vrfctn}.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Service
public class UuidQueryService {

    private static final Logger log = LoggerFactory.getLogger(UuidQueryService.class);

    private final MessageSerializer serializer;
    private final UuidQueryRepository registry;
    private final TransitClient kms;
    private final String encryptionKey;
    private final DatatypeFactory datatypeFactory;

    public UuidQueryService(MessageSerializer serializer, UuidQueryRepository registry,
            TransitClient kms,
            @org.springframework.beans.factory.annotation.Value("${icg.kms.encryption-key}")
            String encryptionKey) {
        this.serializer = serializer;
        this.registry = registry;
        this.kms = kms;
        this.encryptionKey = encryptionKey;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** El PrxyRcrd y el código con el que debe viajar, decididos juntos. */
    public record Response(byte[] body, int httpStatus) {
    }

    public Response handle(byte[] requestBody, String requestingBic) {
        var query = serializer.parse(requestBody, Document.class).getPrxyQry();
        String aliasUuid = uuidOf(query);

        var found = registry.byUuid(aliasUuid);
        if (found.isEmpty()) {
            // El UUID no existe. 404 es el escenario que el Anexo §6 fija para F7,
            // y es el único de las consultas que sí lo tiene: preguntar por un
            // identificador que no existe es distinto de una lista vacía.
            log.info("consulta por uuid: banco={} encontrado=no", requestingBic);
            throw new UuidNotFoundException(aliasUuid);
        }
        var row = found.get();

        // El UUID no es sensible por sí mismo —no dice quién ni qué cuenta— y es
        // lo único que permite rastrear la consulta. El alias, el IBAN y el DPI,
        // NUNCA (regla T-8).
        log.info("consulta por uuid: banco={} activo={} banco_duenio={}",
                requestingBic, row.active(), row.bic());

        return new Response(build(aliasUuid, row), ResponseCode.UUID_QUERY_OK.httpStatus());
    }

    /**
     * Extrae el AliasUUID del criterio de búsqueda.
     *
     * <p>El XSD deja {@code SchCrit} como una elección entre {@code Ownr} (F4) y
     * {@code AliasUUID} (F7). Una consulta por cliente mandada aquí es válida
     * contra el esquema y no es de este servicio, así que se rechaza en vez de
     * seguir con un nulo.
     */
    private static String uuidOf(ProxyQuery1 query) {
        var criteria = query.getSchCrit();
        if (criteria.getAliasUUID() == null) {
            throw new InvalidUuidQueryException("La consulta por UUID exige"
                    + " SchCrit/AliasUUID; la búsqueda por cliente corresponde a F4, no a F7");
        }
        return criteria.getAliasUUID();
    }

    /**
     * Descifra los cuatro campos en UNA llamada al KMS.
     *
     * <p>Es un solo registro, así que el ahorro es menor que en F4, pero el
     * criterio es el mismo y evita que aparezca por aquí la versión de cuatro
     * viajes que después alguien copia a un servicio que sí lista.
     */
    private byte[] build(String aliasUuid, UuidQueryRepository.EncryptedRow row) {
        List<String> plain = kms.decrypt(encryptionKey, List.of(
                row.aliasEnc(), row.ibanEnc(), row.accountTypeEnc(), row.currencyEnc()));

        var proxyType = new ProxyAccountType1Choice();
        proxyType.setCd(row.aliasType());
        var proxy = new ProxyAccountIdentification1();
        proxy.setTp(proxyType);
        proxy.setId(plain.get(0));

        var iban = new AccountIdentification4Choice();
        iban.setIBAN(plain.get(1));
        var accountType = new CashAccountType2Choice();
        accountType.setCd(plain.get(2));
        var account = new CashAccount40();
        account.setId(iban);
        account.setTp(accountType);
        account.setCcy(plain.get(3));
        account.setSvcr(agentOf(row.bic()));

        var record = new ProxyRecord1();
        record.setAliasUUID(aliasUuid);
        record.setPrxy(proxy);
        record.setAcct(account);
        record.setVrfctn(row.active());
        record.setCreDtTm(toXmlCalendar(row.registeredAt()));
        record.getSplmtryData().add(displayLevel(row.nameDisplayLevel()));

        var out = new Document();
        out.setPrxyRcrd(record);
        return serializer.build(new ObjectFactory().createDocument(out));
    }

    /** El NmDsplyLvl del registro, en SupplementaryData (icg.ext.001). */
    private static SupplementaryData1 displayLevel(String level) {
        var envelope = new SupplementaryDataEnvelope1();
        envelope.setAny(new com.icg.aliasdirectory.messaging.icg.ext.ObjectFactory()
                .createNmDsplyLvl(NameDisplayLevel1Code.fromValue(level)));
        var data = new SupplementaryData1();
        data.setEnvlp(envelope);
        return data;
    }

    private static BranchAndFinancialInstitutionIdentification6 agentOf(String bic) {
        var institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic);
        var agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        return agent;
    }

    private XMLGregorianCalendar toXmlCalendar(Timestamp timestamp) {
        return datatypeFactory.newXMLGregorianCalendar(GregorianCalendar.from(
                ZonedDateTime.ofInstant(timestamp.toInstant(), ZoneOffset.UTC)));
    }

    /** La consulta pasó el XSD pero no trae el criterio que F7 necesita. */
    public static class InvalidUuidQueryException extends RuntimeException {
        public InvalidUuidQueryException(String message) {
            super(message);
        }
    }

    /** El AliasUUID consultado no existe (Anexo §6, F7 → 404). */
    public static class UuidNotFoundException extends RuntimeException {
        public UuidNotFoundException(String aliasUuid) {
            super("No existe registro con AliasUUID " + aliasUuid);
        }
    }
}
