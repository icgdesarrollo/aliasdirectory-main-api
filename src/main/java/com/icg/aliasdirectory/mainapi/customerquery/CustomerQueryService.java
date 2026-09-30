package com.icg.aliasdirectory.mainapi.customerquery;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;
import com.icg.aliasdirectory.mainapi.registration.RegistryWriteRepository;
import com.icg.aliasdirectory.messaging.icg.schema.AccountIdentification4Choice;
import com.icg.aliasdirectory.messaging.icg.schema.BranchAndFinancialInstitutionIdentification6;
import com.icg.aliasdirectory.messaging.icg.schema.CashAccount40;
import com.icg.aliasdirectory.messaging.icg.schema.CashAccountType2Choice;
import com.icg.aliasdirectory.messaging.icg.schema.Document;
import com.icg.aliasdirectory.messaging.icg.schema.FinancialInstitutionIdentification18;
import com.icg.aliasdirectory.messaging.icg.schema.GenericPersonIdentification1;
import com.icg.aliasdirectory.messaging.icg.schema.IdentificationAssignment3;
import com.icg.aliasdirectory.messaging.icg.schema.ObjectFactory;
import com.icg.aliasdirectory.messaging.icg.schema.Party40Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyAccountIdentification1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyAccountType1Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyList1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyQuery1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyRecord1;
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
import java.util.List;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * F4 · Consultar los alias de un cliente (PrxyQry → PrxyList).
 *
 * <p>Un banco pregunta por su propio cliente y recibe los alias que ese cliente
 * tiene registrados EN ESE BANCO. El aislamiento no es un filtro añadido: la
 * búsqueda entra por el par (banco, IdCliente), que es la clave de
 * {@code holder_bank}, así que no hay forma de alcanzar el cliente de otra
 * entidad.
 *
 * <p>Un cliente sin alias activos devuelve una lista vacía con HTTP 200. No es
 * un error y no debe serlo: «este cliente no tiene alias» es una respuesta
 * legítima, y un 404 haría que el banco la trate como una falla.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Service
public class CustomerQueryService {

    private static final Logger log = LoggerFactory.getLogger(CustomerQueryService.class);

    /** SchmeNm.Prtry con el que el banco marca el IdCliente dentro de Ownr. */
    private static final String CUSTOMER_ID_SCHEME = "CUSTOMER_ID";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final CustomerQueryRepository registry;
    private final RegistryWriteRepository banks;
    private final TransitClient kms;
    private final String encryptionKey;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    public CustomerQueryService(MessageSerializer serializer, BlindIndex index,
            CustomerQueryRepository registry, RegistryWriteRepository banks, TransitClient kms,
            @Value("${icg.kms.encryption-key}") String encryptionKey,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.index = index;
        this.registry = registry;
        this.banks = banks;
        this.kms = kms;
        this.encryptionKey = encryptionKey;
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** @return el PrxyList listo para devolverle al banco */
    public byte[] handle(byte[] requestBody, String requestingBic) {
        var query = serializer.parse(requestBody, Document.class).getPrxyQry();
        String customerId = customerIdOf(query);

        int bankId = banks.bankId(requestingBic).orElseThrow(
                () -> new IllegalStateException("El BIC " + requestingBic + " no está en"
                        + " participant_bank. El borde lo dejó pasar y no debería."));

        byte[] customerBidx = index.ofCustomerId(Normalizer.customerId(customerId));
        var rows = registry.aliasesOfCustomer(bankId, customerBidx);

        // El IdCliente NUNCA, ni siquiera cifrado (regla T-8). El banco y el
        // tamaño de la lista bastan para diagnosticar, y no dicen de quién es.
        log.info("consulta por cliente: banco={} registros={}", requestingBic, rows.size());

        return build(query, requestingBic, decrypt(rows));
    }

    /**
     * Extrae el IdCliente del criterio de búsqueda.
     *
     * <p>El XSD deja {@code SchCrit} como una elección entre {@code Ownr} (F4) y
     * {@code AliasUUID} (F7). Un mensaje con AliasUUID es válido contra el
     * esquema y no es de este servicio, así que se rechaza explícitamente en
     * vez de seguir con un nulo.
     */
    private static String customerIdOf(ProxyQuery1 query) {
        var criteria = query.getSchCrit();
        if (criteria.getOwnr() == null) {
            throw new InvalidQueryException("La consulta por cliente exige SchCrit/Ownr;"
                    + " la búsqueda por AliasUUID corresponde a F7, no a F4");
        }
        var privateId = criteria.getOwnr().getId().getPrvtId();
        if (privateId != null) {
            for (GenericPersonIdentification1 other : privateId.getOthr()) {
                if (other.getSchmeNm() != null
                        && CUSTOMER_ID_SCHEME.equals(other.getSchmeNm().getPrtry())) {
                    return other.getId();
                }
            }
        }
        throw new InvalidQueryException("SchCrit/Ownr no trae ningún Othr con"
                + " SchmeNm.Prtry=" + CUSTOMER_ID_SCHEME);
    }

    /**
     * Descifra las cuatro columnas de todas las filas en UNA sola llamada al KMS.
     *
     * <p>Fila por fila serían cuatro viajes por alias; un cliente con cinco
     * alias son veinte. Medido en E04-D02: 0.99 ms por identificador en lote
     * contra 4.84 ms suelto. Por eso se aplanan todos los ciphertexts, se pide
     * una vez y se reparten en el mismo orden.
     */
    private List<Record> decrypt(List<CustomerQueryRepository.EncryptedRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var ciphertexts = new ArrayList<String>(rows.size() * 4);
        for (var row : rows) {
            ciphertexts.add(row.aliasEnc());
            ciphertexts.add(row.ibanEnc());
            ciphertexts.add(row.accountTypeEnc());
            ciphertexts.add(row.currencyEnc());
        }
        List<String> plain = kms.decrypt(encryptionKey, ciphertexts);

        var records = new ArrayList<Record>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int base = i * 4;
            records.add(new Record(row.aliasUuid(), row.aliasType(), plain.get(base),
                    plain.get(base + 1), plain.get(base + 2), plain.get(base + 3), row.bic()));
        }
        return records;
    }

    private byte[] build(ProxyQuery1 query, String requestingBic, List<Record> records) {
        var list = new ProxyList1();
        list.setAssgnmt(responseTo(query.getAssgnmt(), requestingBic));
        for (Record r : records) {
            list.getPrxyRcrd().add(record(r));
        }

        var out = new Document();
        out.setPrxyList(list);
        return serializer.build(new ObjectFactory().createDocument(out));
    }

    private static ProxyRecord1 record(Record r) {
        var proxy = new ProxyAccountIdentification1();
        var proxyType = new ProxyAccountType1Choice();
        proxyType.setCd(r.aliasType());
        proxy.setTp(proxyType);
        proxy.setId(r.alias());

        var ibanChoice = new AccountIdentification4Choice();
        ibanChoice.setIBAN(r.iban());
        var accountType = new CashAccountType2Choice();
        accountType.setCd(r.accountType());
        var account = new CashAccount40();
        account.setId(ibanChoice);
        account.setTp(accountType);
        account.setCcy(r.currency());
        account.setSvcr(agentOf(r.bic()));

        var record = new ProxyRecord1();
        record.setAliasUUID(r.aliasUuid());
        record.setPrxy(proxy);
        record.setAcct(account);
        // Vrfctn y CreDtTm quedan fuera a propósito: el XSD los marca opcionales
        // porque son de F7. En la lista de F4 no aportan y el Anexo no los pide.
        return record;
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
                java.util.GregorianCalendar.from(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)));
    }

    /** Una fila ya descifrada, lista para volverse PrxyRcrd. */
    private record Record(String aliasUuid, String aliasType, String alias, String iban,
            String accountType, String currency, String bic) {
    }

    /** La consulta pasó el XSD pero no trae el criterio que F4 necesita. */
    public static class InvalidQueryException extends RuntimeException {
        public InvalidQueryException(String message) {
            super(message);
        }
    }
}
