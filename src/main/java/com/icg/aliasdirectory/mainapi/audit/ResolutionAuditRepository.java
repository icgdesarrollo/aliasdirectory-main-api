package com.icg.aliasdirectory.mainapi.audit;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Base64;

/**
 * Escribe el evento de resolución y su detalle.
 *
 * <p>La tabla está particionada por fecha y no admite claves foráneas, así que las
 * referencias a {@code alias_registration} y {@code participant_bank} las valida
 * la capa de servicios. Lo que sí impone el motor son tres CHECK, y los INSERT de
 * aquí están escritos para cumplirlos:
 *
 * <ul>
 *   <li>{@code ck_resolution_event_part_date} — part_date se calcula con DATE()
 *       sobre el mismo instante, no se pasa aparte.
 *   <li>{@code ck_resolution_event_orgtr_by_method} — RESOLUCION exige
 *       orgtr_cust_id_enc.
 *   <li>{@code ck_resolution_event_returned_evt} — RESOLUCION exige
 *       returned_evt_alias = 1; los otros dos métodos, 0.
 * </ul>
 */
@Repository
public class ResolutionAuditRepository {

    /**
     * Idempotente por (evt_alias, part_date), que es la clave primaria.
     *
     * <p>El {@code ON DUPLICATE KEY UPDATE evt_alias = evt_alias} es un no-op a
     * propósito: si el mensaje se entrega dos veces —y con reintentos y una DLQ se
     * entrega dos veces tarde o temprano— la segunda no debe fallar ni reescribir
     * nada. Un UPDATE real dejaría la bitácora reflejando el REINTENTO en vez de
     * la consulta, que es justo lo que una auditoría no puede hacer.
     *
     * <p>UUID_TO_BIN con el segundo argumento en 1 reordena el UUID para que los
     * valores consecutivos queden juntos en el índice; es la misma convención que
     * alias_registration.alias_uuid.
     */
    private static final String INSERT_EVENT = """
            INSERT INTO resolution_event
                   (evt_alias, part_date, event_at, msg_id, msg_id_rpt, channel, method,
                    alias_enc, alias_bidx, alias_bidx_ver,
                    orgtr_cust_id_enc, orgtr_cust_id_bidx, orgtr_cust_bidx_ver,
                    requesting_bank_enc, vrfctn, reason_prtry, http_status,
                    returned_records, returned_evt_alias, latency_ms, dispatch_ms,
                    amqps_queue, origin_node, correlation_id)
            VALUES (UUID_TO_BIN(:evtAlias, 1), DATE(:eventAt), :eventAt, :msgId, :msgIdRpt,
                    :channel, :method,
                    :aliasEnc, :aliasBidx, :aliasBidxVer,
                    :orgtrEnc, :orgtrBidx, :orgtrVer,
                    :bancoEnc, :vrfctn, :reasonPrtry, :httpStatus,
                    :returnedRecords, :returnedEvtAlias, :latencyMs, :dispatchMs,
                    :amqpsQueue, :originNode, :correlationId)
            ON DUPLICATE KEY UPDATE evt_alias = evt_alias
            """;

    private static final String INSERT_DETAIL = """
            INSERT INTO resolution_event_detail
                   (evt_alias, part_date, position, alias_registration_id, bank_id)
            VALUES (UUID_TO_BIN(:evtAlias, 1), DATE(:eventAt), :position, :registrationId, :bankId)
            ON DUPLICATE KEY UPDATE position = position
            """;

    private final JdbcClient jdbc;

    public ResolutionAuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * El evento y su detalle, en una transacción.
     *
     * <p>Van juntos porque un evento que dice «devolví 3 registros» sin las tres
     * filas de detalle es una bitácora que se contradice a sí misma.
     */
    @Transactional
    public void save(ResolutionAuditEvent e) {
        Timestamp eventAt = new Timestamp(e.eventAtMillis());
        jdbc.sql(INSERT_EVENT)
                .param("evtAlias", e.evtAlias())
                .param("eventAt", eventAt)
                .param("msgId", e.msgId())
                .param("msgIdRpt", e.msgIdRpt())
                .param("channel", e.channel())
                .param("method", e.method())
                .param("aliasEnc", bytes(e.aliasEnc()))
                .param("aliasBidx", decode(e.aliasBidx()))
                .param("aliasBidxVer", e.aliasBidxVer())
                .param("orgtrEnc", bytes(e.orgtrCustIdEnc()))
                .param("orgtrBidx", decode(e.orgtrCustIdBidx()))
                .param("orgtrVer", e.orgtrCustBidxVer())
                .param("bancoEnc", bytes(e.requestingBankEnc()))
                .param("vrfctn", e.vrfctn())
                .param("reasonPrtry", e.reasonPrtry())
                .param("httpStatus", e.httpStatus())
                .param("returnedRecords", e.returnedRecords())
                .param("returnedEvtAlias", e.returnedEvtAlias())
                .param("latencyMs", e.latencyMs())
                .param("dispatchMs", e.dispatchMs())
                .param("amqpsQueue", e.amqpsQueue())
                .param("originNode", e.originNode())
                .param("correlationId", e.correlationId())
                .update();

        // El detalle calcula part_date con DATE() sobre el MISMO Timestamp que el
        // evento, y no con una fecha armada en Java. Calcularla aparte parece
        // equivalente y no lo es: DATE() la resuelve en la zona de la conexión y
        // LocalDate en la que elija el código, así que a las 2 de la mañana UTC
        // con el servidor en GMT-6 caerían en días distintos. El detalle acabaría
        // en otra partición que el evento al que pertenece.
        for (ResolutionAuditEvent.Detail d : e.detail()) {
            jdbc.sql(INSERT_DETAIL)
                    .param("evtAlias", e.evtAlias())
                    .param("eventAt", eventAt)
                    .param("position", d.position())
                    .param("registrationId", d.aliasRegistrationId())
                    .param("bankId", d.bankId())
                    .update();
        }
    }

    /** El ciphertext de Transit es ASCII; la columna es VARBINARY. */
    private static byte[] bytes(String ciphertext) {
        return ciphertext == null ? null
                : ciphertext.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Las huellas viajan en Base64 por el JSON; la columna es binary(32). */
    private static byte[] decode(String base64) {
        return base64 == null ? null : Base64.getDecoder().decode(base64);
    }
}
