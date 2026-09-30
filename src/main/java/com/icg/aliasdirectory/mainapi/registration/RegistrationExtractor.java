package com.icg.aliasdirectory.mainapi.registration;

import com.icg.aliasdirectory.messaging.icg.schema.GenericPersonIdentification1;
import com.icg.aliasdirectory.messaging.icg.schema.ProxyRegistration1;
import com.icg.aliasdirectory.messaging.icg.schema.SupplementaryData1;

import org.w3c.dom.Element;

/**
 * Saca del PrxyRegn los datos que el padrón necesita.
 *
 * <p>El XSD ya garantizó la forma —Dispatch valida antes de encolar—, así que
 * aquí no se revalida nada que el esquema cubra. Lo que sí se comprueba es lo
 * que el esquema no puede: que el {@code Ownr} traiga los dos identificadores
 * que el registro exige. El XSD los permite en cualquier orden y cualquier
 * cantidad, porque {@code Othr} es una lista; que estén los dos y una sola vez
 * es regla nuestra.
 */
final class RegistrationExtractor {

    /** Ownr.Id.PrvtId.Othr con SchmeNm.Cd = NIDN. */
    private static final String DPI_SCHEME = "NIDN";

    /** Ownr.Id.PrvtId.Othr con SchmeNm.Prtry = CUSTOMER_ID. */
    private static final String CUSTOMER_ID_SCHEME = "CUSTOMER_ID";

    /** Elemento de icg.ext.001 dentro de SupplementaryData. */
    private static final String NAME_LEVEL = "NmDsplyLvl";

    /**
     * Lo que dice el modelo de datos cuando el banco no manda NmDsplyLvl.
     *
     * <p>Coincide con el DEFAULT de la columna, y es el valor más restrictivo de
     * los tres que revela algo: ante el silencio del banco no se asume que el
     * titular quiere su nombre completo a la vista.
     */
    private static final String DEFAULT_LEVEL = "MASKED";

    private RegistrationExtractor() {
    }

    static RegistrationData from(ProxyRegistration1 registration) {
        var owner = registration.getOwnr();
        if (owner == null || owner.getId() == null
                || owner.getId().getPrvtId() == null) {
            throw new InvalidRegistrationException("El registro no trae Ownr.Id.PrvtId");
        }
        var identifiers = owner.getId().getPrvtId().getOthr();

        String dpi = single(identifiers, DPI_SCHEME, true);
        String customerId = single(identifiers, CUSTOMER_ID_SCHEME, false);

        var account = registration.getAcct();
        return new RegistrationData(
                registration.getPrxy().getId(),
                dpi,
                customerId,
                account.getId().getIBAN(),
                account.getTp() == null ? null : account.getTp().getCd(),
                account.getCcy(),
                nameDisplayLevel(registration),
                registration.getAssgnmt().getMsgId());
    }

    /**
     * El único identificador con ese esquema, o error.
     *
     * <p>Se exige exactamente uno y no «el primero que aparezca». Dos DPI
     * distintos en el mismo Ownr no es un mensaje al que haya que encontrarle
     * sentido: es una solicitud que no se sabe a nombre de quién va, y elegir
     * uno en silencio registraría el alias a nombre de una persona que el banco
     * no nombró sin ambigüedad.
     *
     * @param porCodigo true mira SchmeNm.Cd (dominio ISO), false mira Prtry (propietario)
     */
    private static String single(java.util.List<GenericPersonIdentification1> identifiers,
            String esquema, boolean porCodigo) {
        String encontrado = null;
        for (var id : identifiers) {
            var nombre = id.getSchmeNm();
            if (nombre == null) {
                continue;
            }
            String value = porCodigo ? nombre.getCd() : nombre.getPrtry();
            if (!esquema.equals(value)) {
                continue;
            }
            if (encontrado != null) {
                throw new InvalidRegistrationException(
                        "El registro trae más de un identificador " + esquema);
            }
            encontrado = id.getId();
        }
        if (encontrado == null || encontrado.isBlank()) {
            throw new InvalidRegistrationException(
                    "El registro no trae el identificador " + esquema);
        }
        return encontrado;
    }

    /**
     * NmDsplyLvl de SupplementaryData, o MASKED.
     *
     * <p>El envoltorio es {@code xs:any}, así que JAXB lo entrega como DOM y hay
     * que recorrerlo a mano. Se compara sólo el nombre local: el prefijo del
     * espacio de nombres lo elige quien genera el XML y no es parte del contrato.
     */
    private static String nameDisplayLevel(ProxyRegistration1 registration) {
        for (SupplementaryData1 suplemento : registration.getSplmtryData()) {
            if (suplemento.getEnvlp() == null
                    || !(suplemento.getEnvlp().getAny() instanceof Element elemento)) {
                continue;
            }
            if (NAME_LEVEL.equals(elemento.getLocalName())) {
                String value = elemento.getTextContent();
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
        }
        return DEFAULT_LEVEL;
    }

    /** El mensaje pasó el esquema pero no cumple una regla del registro. */
    static class InvalidRegistrationException extends RuntimeException {
        InvalidRegistrationException(String message) {
            super(message);
        }
    }
}
