package com.icg.aliasdirectory.mainapi.registration;

import com.icg.aliasdirectory.mainapi.availability.Reason;
import com.icg.aliasdirectory.messaging.icg.schema.BranchAndFinancialInstitutionIdentification6;
import com.icg.aliasdirectory.messaging.icg.schema.Document;
import com.icg.aliasdirectory.messaging.icg.schema.FinancialInstitutionIdentification18;
import com.icg.aliasdirectory.messaging.icg.schema.IdentificationAssignment3;
import com.icg.aliasdirectory.messaging.icg.schema.ObjectFactory;
import com.icg.aliasdirectory.messaging.icg.schema.OriginalAssignment1;
import com.icg.aliasdirectory.messaging.icg.schema.Party40Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyReason1Code;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyRegistration1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyRegistrationReport1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatus1Choice;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatus1Code;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatusReason1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyStatusReport1;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Arma el PrxyRegnRpt, tanto el de alta aceptada como el de rechazo.
 *
 * <p>Separado del servicio porque son dos trabajos distintos: uno decide qué
 * pasó con el registro y el otro lo dice en ISO 20022. Mezclarlos hace que el
 * primero sea difícil de leer entre tanto {@code setX(new Y())}.
 */
// Solo lo usa el registro, que a su vez solo existe con el KMS encendido.
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Component
public class ResponseBuilder {

    /** Mismo formato que usa la disponibilidad: DRGRPT-20260922-230914863. */
    private static final DateTimeFormatter SELLO =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS").withZone(ZoneOffset.UTC);

    private final MessageSerializer serializer;
    private final String directoryBic;
    private final DatatypeFactory datatypeFactory;

    public ResponseBuilder(MessageSerializer serializer,
            @Value("${icg.directory.bic:ICGSGTGC}") String directoryBic) {
        this.serializer = serializer;
        this.directoryBic = directoryBic;
        try {
            this.datatypeFactory = DatatypeFactory.newInstance();
        } catch (DatatypeConfigurationException e) {
            throw new IllegalStateException("No se pudo crear la fábrica de fechas XML", e);
        }
    }

    /** Alta aceptada: Sts = ACTV, con su RegnId y la fecha de registro. HTTP 201. */
    public Response accepted(ProxyRegistration1 request,
            AliasRegistrar.Registrado registrado) {
        var status = new ProxyStatusReport1();
        status.setRegnId(registrado.regnId());
        status.setSts(code(ProxyStatus1Code.ACTV));
        status.setRegnDtTm(toXmlCalendar(registrado.registeredAt()));
        return new Response(build(request, status), 201);
    }

    /**
     * Alta rechazada: Sts = RJCT, con el motivo.
     *
     * <p>{@code AddtlInf} lleva el BIC del banco en conflicto cuando el motivo es
     * de otra entidad, y nada más. Nunca el alias, el DPI ni el IBAN: el campo
     * viaja de vuelta al banco y quedaría en sus propios registros, que es
     * exactamente lo que la regla T-8 evita de este lado.
     *
     * <p>El motivo puede ser nulo cuando el alias está tomado pero la regla no
     * supo decir por qué —no debería ocurrir, y si ocurre es preferible un
     * rechazo sin motivo que inventar uno.
     */
    public Response rejected(ProxyRegistration1 request, Reason reason, String conflictingBic) {
        var status = new ProxyStatusReport1();
        status.setSts(code(ProxyStatus1Code.RJCT));
        if (reason != null) {
            var statusReason = new ProxyStatusReason1();
            statusReason.setPrtry(ProxyReason1Code.fromValue(reason.name()));
            status.setRsn(statusReason);
        }
        if (conflictingBic != null) {
            status.setAddtlInf("Registrado en " + conflictingBic);
        }
        // 409 y no 201: no se creó nada. El cuerpo ya lo decía con Sts=RJCT, pero la
        // cabecera decía lo contrario, y hay integraciones que sólo miran el código.
        // Un 2xx sobre un alta rechazada le dice al banco que el alias es suyo.
        return new Response(build(request, status), 409);
    }

    /**
     * El XML y el código HTTP con el que debe viajar.
     *
     * <p>Van juntos a propósito: quien decide si el alta se aceptó es esta clase, y
     * separar el cuerpo del código deja que el consumidor elija uno que no
     * corresponde —que es exactamente lo que pasaba—.
     */
    public record Response(byte[] body, int httpStatus) {
    }

    private byte[] build(ProxyRegistration1 request, ProxyStatusReport1 status) {
        var report = new ProxyRegistrationReport1();
        report.setAssgnmt(responseTo(request.getAssgnmt()));
        report.setOrgnlAssgnmt(original(request.getAssgnmt()));
        report.setPrxy(request.getPrxy());
        report.setStsRpt(status);

        var documento = new Document();
        documento.setPrxyRegnRpt(report);
        return serializer.build(new ObjectFactory().createDocument(documento));
    }

    /**
     * Cabecera de la respuesta, con Assgnr y Assgne invertidos (regla T-4).
     *
     * <p>El destinatario se toma del {@code Assgnr} de la solicitud y no del BIC
     * del canal: son el mismo valor —el borde rechaza con 403 si difieren— y
     * usar el del mensaje deja la respuesta coherente con lo que el banco mandó.
     */
    private IdentificationAssignment3 responseTo(IdentificationAssignment3 original) {
        var assignment = new IdentificationAssignment3();
        // MsgId PROPIO, no derivado del de la solicitud. Derivarlo parecía cómodo y
        // rompía la unicidad: dos respuestas al mismo MsgId —la que registra y el
        // rechazo del reintento— salían con el mismo identificador, y el banco que
        // archive por MsgId pierde la primera, que es la que trae el RegnId.
        // El vínculo con la solicitud es OrgnlAssgnmt; para eso existe.
        assignment.setMsgId("PRXRPT-" + SELLO.format(Instant.now()));
        assignment.setCreDtTm(toXmlCalendar(Instant.now()));
        assignment.setAssgnr(agent(directoryBic));
        assignment.setAssgne(original.getAssgnr());
        return assignment;
    }

    private OriginalAssignment1 original(IdentificationAssignment3 request) {
        var original = new OriginalAssignment1();
        original.setMsgId(request.getMsgId());
        original.setCreDtTm(request.getCreDtTm());
        return original;
    }

    private static ProxyStatus1Choice code(ProxyStatus1Code value) {
        var status = new ProxyStatus1Choice();
        status.setCd(value);
        return status;
    }

    private static Party40Choice agent(String bic) {
        var institution = new FinancialInstitutionIdentification18();
        institution.setBICFI(bic);
        var agent = new BranchAndFinancialInstitutionIdentification6();
        agent.setFinInstnId(institution);
        var party = new Party40Choice();
        party.setAgt(agent);
        return party;
    }

    private XMLGregorianCalendar toXmlCalendar(Instant instant) {
        return datatypeFactory.newXMLGregorianCalendar(
                java.util.GregorianCalendar.from(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC)));
    }
}
