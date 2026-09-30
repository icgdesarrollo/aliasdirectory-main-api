package com.icg.aliasdirectory.mainapi.registration;

/**
 * Lo que trae un PrxyRegn, ya extraído del XML y todavía en claro.
 *
 * <p>Vive poco: se arma al desarmar el mensaje y se descarta al terminar la
 * transacción. Nada de esto se registra en bitácora —alias, DPI, IdCliente e
 * IBAN son exactamente lo que la regla T-8 prohíbe escribir en un log— y por eso
 * el record no lleva {@code toString} propio: el que genera el compilador
 * imprimiría los cuatro campos, y basta con que alguien lo interpole en un
 * mensaje de log por descuido para filtrarlos todos de una vez.
 *
 * @param alias      Prxy.Id, tal como vino; se normaliza a E.164 más adelante
 * @param dpi        Ownr con SchmeNm.Cd = NIDN
 * @param customerId  Ownr con SchmeNm.Prtry = CUSTOMER_ID
 * @param iban       Acct.Id.IBAN
 * @param accountType Acct.Tp.Cd (SVGS | CACC)
 * @param moneda     Acct.Ccy (ISO 4217)
 * @param nivelNombre  NmDsplyLvl de SupplementaryData; MASKED si no viene
 * @param msgIdOrigen  Assgnmt.MsgId de la solicitud, para poder rastrearla
 */
public record RegistrationData(
        String alias,
        String dpi,
        String customerId,
        String iban,
        String accountType,
        String currency,
        String nivelNombre,
        String msgIdOrigen) {

    /**
     * Oculta los datos del titular. Sobrescribe el {@code toString} del record a
     * propósito: el generado los expondría todos.
     */
    @Override
    public String toString() {
        return "RegistrationData[msgIdOrigen=" + msgIdOrigen + ", resto oculto]";
    }
}
