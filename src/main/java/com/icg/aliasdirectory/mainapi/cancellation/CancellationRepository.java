package com.icg.aliasdirectory.mainapi.cancellation;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Lee y cambia el estado de los registros que F5 da de baja.
 *
 * <p>Las dos consultas traen, además de lo que la decisión necesita, <b>los
 * campos del sello de integridad</b>. No es de más: {@code status} entra en el
 * sello (H-57), así que una baja que cambie el estado sin recalcularlo dejaría
 * el registro marcado como alterado y la siguiente consulta de ese alias
 * fallaría la verificación. El sello se recalcula en la misma transacción.
 *
 * <p>Nunca se borra una fila. La baja es lógica —a INACTIVO, o a BLOQUEADO
 * cuando toca cuarentena— y queda rastro en {@code alias_registration_history}.
 */
@Repository
public class CancellationRepository {

    /** Lo que hay que saber del registro para darlo de baja y volver a sellarlo. */
    private static final String COLUMNS = """
            SELECT r.id                          AS id,
                   BIN_TO_UUID(r.alias_uuid, 1)  AS alias_uuid,
                   r.regn_id                     AS regn_id,
                   r.bank_id                     AS bank_id,
                   b.bicfi                       AS bic,
                   r.status                      AS status,
                   r.nm_dsply_lvl                AS nm_dsply_lvl,
                   r.registered_at               AS registered_at,
                   a.value_bidx                  AS alias_bidx,
                   h.dpi_bidx                    AS dpi_bidx,
                   c.iban_enc                    AS iban_enc
              FROM alias_registration r
              JOIN alias              a ON a.id      = r.alias_id
              JOIN holder             h ON h.id      = r.holder_id
              JOIN account            c ON c.id      = r.account_id
                                       AND c.bank_id = r.bank_id
              JOIN participant_bank   b ON b.id      = r.bank_id
            """;

    private static final String BY_UUID = COLUMNS + """
             WHERE r.alias_uuid = UUID_TO_BIN(:aliasUuid, 1)
            """;

    /**
     * Todos los registros vigentes del alias, en cualquier entidad (ALL_BANKS).
     *
     * <p>ACTIVO y BLOQUEADO: los dos ocupan el cupo alias×banco y los dos son
     * susceptibles de baja. Uno ya INACTIVO no vuelve a darse de baja, y por eso
     * no entra.
     */
    private static final String ACTIVE_OF_ALIAS = COLUMNS + """
             WHERE a.type_cd    = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
             ORDER BY r.registered_at
            """;

    private static final String CANCEL = """
            UPDATE alias_registration
               SET status            = :status,
                   quarantine_until  = :quarantineUntil,
                   reason_prtry      = :reason,
                   status_changed_at = :changedAt
             WHERE id = :id
            """;

    private static final String HISTORY = """
            INSERT INTO alias_registration_history
                   (alias_registration_id, previous_status, new_status, reason_prtry,
                    cxl_scp, origin, bank_id, portal_user_id, msg_id)
            VALUES (:id, :previousStatus, :newStatus, :reason,
                    :scope, :origin, :bankId, :portalUserId, :msgId)
            """;

    private final JdbcClient jdbc;

    public CancellationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Registration> byUuid(String aliasUuid) {
        return jdbc.sql(BY_UUID).param("aliasUuid", aliasUuid).query(Registration.MAPPER).optional();
    }

    public List<Registration> activeOfAlias(String tipo, byte[] aliasBidx) {
        return jdbc.sql(ACTIVE_OF_ALIAS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query(Registration.MAPPER)
                .list();
    }

    /**
     * Cambia el estado del registro.
     *
     * @param quarantineUntil fin del período de gracia, o {@code null} cuando la
     *                        baja es inmediata. La restricción
     *                        {@code ck_registration_quarantine} exige que vaya
     *                        con BLOQUEADO y sólo con él.
     */
    public void cancel(long id, String status, Instant quarantineUntil, String reason,
            Instant changedAt) {
        jdbc.sql(CANCEL)
                .param("id", id)
                .param("status", status)
                .param("quarantineUntil",
                        quarantineUntil == null ? null : Timestamp.from(quarantineUntil))
                .param("reason", reason)
                .param("changedAt", Timestamp.from(changedAt))
                .update();
    }

    /**
     * Deja el cambio en el histórico.
     *
     * @param origin       canal por el que entró la baja: API cuando la pidió el
     *                     banco por ISO 20022, PORTAL cuando la aprobó un agente.
     *                     Sin esta distinción una auditoría no puede separar lo
     *                     que hizo un banco de lo que hizo Call Center.
     * @param portalUserId el agente que la ejecutó, o null cuando no vino del
     *                     portal. Es quien aprobó, no quien la capturó: es la
     *                     persona cuya decisión cambió el estado. Al solicitante
     *                     se llega por el msgId, que nombra la solicitud.
     */
    public void recordHistory(long registrationId, String previousStatus, String newStatus,
            String reason, String scope, String origin, int bankId, Long portalUserId,
            String msgId) {
        jdbc.sql(HISTORY)
                .param("id", registrationId)
                .param("previousStatus", previousStatus)
                .param("newStatus", newStatus)
                .param("reason", reason)
                .param("scope", scope)
                .param("origin", origin)
                .param("bankId", bankId)
                .param("portalUserId", portalUserId)
                .param("msgId", msgId)
                .update();
    }

    /**
     * Un registro con lo que F5 necesita: la decisión y el sello.
     *
     * @param registeredAt cuándo se dio de alta; decide si la baja pasa por
     *                     cuarentena o es inmediata
     */
    public record Registration(long id, String aliasUuid, String regnId, int bankId, String bic,
            String status, String nameDisplayLevel, Timestamp registeredAt, byte[] aliasBidx,
            byte[] dpiBidx, byte[] ibanEnc) {

        static final org.springframework.jdbc.core.RowMapper<Registration> MAPPER =
                (rs, row) -> new Registration(
                        rs.getLong("id"),
                        rs.getString("alias_uuid"),
                        rs.getString("regn_id"),
                        rs.getInt("bank_id"),
                        rs.getString("bic"),
                        rs.getString("status"),
                        rs.getString("nm_dsply_lvl"),
                        rs.getTimestamp("registered_at"),
                        rs.getBytes("alias_bidx"),
                        rs.getBytes("dpi_bidx"),
                        rs.getBytes("iban_enc"));
    }
}
