package com.icg.aliasdirectory.mainapi.audit;

import java.util.List;

/**
 * Lo que viaja por la cola hacia {@code resolution_event}.
 *
 * <p><b>Todo lo sensible viaja YA CIFRADO.</b> No es un detalle de implementación:
 * una cola persistente conserva el mensaje en disco hasta que alguien lo consume,
 * y un mensaje con el alias y el DPI en claro sería exactamente lo que la regla
 * T-8 evita en los logs, sólo que peor —un log rota, una cola con reintentos
 * puede guardar el mensaje días—. El cifrado lo hace el publicador en UNA llamada
 * en lote al KMS; ver {@link ResolutionAuditPublisher}.
 *
 * <p>Los nombres son los de las columnas a propósito. Este record no es un modelo
 * de dominio: es el formato de un mensaje que se escribe tal cual en una tabla, y
 * que alguien tendrá que leer con el DDL al lado cuando algo no cuadre.
 *
 * @param evtAlias          icg.ext.003 EvtAlias, el identificador de la consulta
 * @param eventAtMillis     momento de la consulta en epoch-millis; de aquí sale
 *                          part_date. Es un long y no un Instant a propósito: el
 *                          formato de java.time en JSON depende de qué módulos
 *                          tenga registrado el mapper, y aquí publicador y
 *                          consumidor son procesos distintos que pueden no
 *                          coincidir. Un entero no admite interpretación, y para
 *                          una bitácora un instante exacto en UTC es justo lo que
 *                          se quiere guardar
 * @param msgId             Assgnmt.MsgId de la solicitud
 * @param msgIdRpt          MsgId de la respuesta, cuando la hubo
 * @param channel           REST o AMQPS: por dónde entró el banco
 * @param method            RESOLUCION, DISP_REGISTRO o DISP_RESOLUCION
 * @param aliasEnc          alias consultado, cifrado
 * @param aliasBidx         huella del alias, en Base64 (la columna es binary(32))
 * @param aliasBidxVer      versión de la llave con que se calculó
 * @param orgtrCustIdEnc    IdCliente originador cifrado. NULL fuera de RESOLUCION
 * @param orgtrCustIdBidx   su huella, en Base64
 * @param orgtrCustBidxVer  versión de esa huella
 * @param requestingBankEnc BIC del banco consultante, cifrado y sin huella
 * @param vrfctn            lo que se respondió en Rpt.Vrfctn
 * @param reasonPrtry       Rsn.Prtry cuando aplica
 * @param httpStatus        código con que se respondió
 * @param returnedRecords   cardinalidad de UpdtdPtyAndAcctId
 * @param returnedEvtAlias  si el EvtAlias viajó en la respuesta
 * @param latencyMs         lo que tardó el servicio
 * @param dispatchMs        tiempo entre el encolado y el consumo, si se conoce
 * @param amqpsQueue        cola por la que entró
 * @param originNode        pod o centro de datos que atendió
 * @param correlationId     identificador de traza
 * @param detail            los registros devueltos; vacío si no hubo
 */
public record ResolutionAuditEvent(
        String evtAlias,
        long eventAtMillis,
        String msgId,
        String msgIdRpt,
        String channel,
        String method,
        String aliasEnc,
        String aliasBidx,
        int aliasBidxVer,
        String orgtrCustIdEnc,
        String orgtrCustIdBidx,
        Integer orgtrCustBidxVer,
        String requestingBankEnc,
        boolean vrfctn,
        String reasonPrtry,
        int httpStatus,
        int returnedRecords,
        boolean returnedEvtAlias,
        Integer latencyMs,
        Integer dispatchMs,
        String amqpsQueue,
        String originNode,
        String correlationId,
        List<Detail> detail) {

    /** Un registro devuelto por la resolución. Va a resolution_event_detail. */
    public record Detail(int position, long aliasRegistrationId, int bankId) {
    }

    /** Los tres valores del ENUM method. Aquí para no repetir literales. */
    public static final String RESOLUCION = "RESOLUCION";
    public static final String DISP_REGISTRO = "DISP_REGISTRO";
    public static final String DISP_RESOLUCION = "DISP_RESOLUCION";
}
