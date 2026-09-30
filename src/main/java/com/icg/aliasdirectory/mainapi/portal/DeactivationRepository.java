package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Las solicitudes de baja del portal, sobre {@code portal_deactivation_request}.
 *
 * <p>La tabla la creó la migración V1.0.2 con el doble control del Anexo §8.2 ya
 * expresado como restricciones del motor: {@code ck_deactivation_request_dual_control}
 * impide que el aprobador sea el solicitante, y los otros dos CHECK exigen
 * aprobador y fechas coherentes con el estado. Este repositorio no vuelve a
 * comprobar lo que el motor ya garantiza; comprueba lo que el motor no puede
 * ver, como que la solicitud sea del banco de quien la mira.
 */
@Repository
public class DeactivationRepository {

    private static final String INSERT = """
            INSERT INTO portal_deactivation_request
                   (alias_registration_id, cxl_scp, requesting_bank_id, requester_id,
                    status, reason, requested_at, expires_at)
            VALUES (:registrationId, :scope, :bankId, :requesterId,
                    'PENDIENTE', :reason, :requestedAt, :expiresAt)
            """;

    private static final String PENDING_FOR_REGISTRATION = """
            SELECT COUNT(*)
              FROM portal_deactivation_request
             WHERE alias_registration_id = :registrationId
               AND status = 'PENDIENTE'
            """;

    private static final String COLUMNS = """
            SELECT d.id                          AS id,
                   d.alias_registration_id       AS registration_id,
                   BIN_TO_UUID(r.alias_uuid, 1)  AS alias_uuid,
                   al.value_enc                  AS alias_enc,
                   d.requesting_bank_id          AS bank_id,
                   b.bicfi                       AS bic,
                   b.entity_name                 AS bank_name,
                   d.status                      AS status,
                   d.reason                      AS reason,
                   d.requester_id                AS requester_id,
                   ru.username                   AS requested_by,
                   d.requested_at                AS requested_at,
                   au.username                   AS resolved_by,
                   d.resolved_at                 AS resolved_at,
                   d.approver_comment            AS approver_comment,
                   d.expires_at                  AS expires_at
              FROM portal_deactivation_request d
              JOIN alias_registration r  ON r.id  = d.alias_registration_id
              JOIN alias              al ON al.id = r.alias_id
              JOIN participant_bank   b  ON b.id  = d.requesting_bank_id
              JOIN portal_user        ru ON ru.id = d.requester_id
              LEFT JOIN portal_user   au ON au.id = d.approver_id
            """;

    private static final String BY_BANK = COLUMNS + """
             WHERE d.requesting_bank_id = :bankId
             ORDER BY d.status = 'PENDIENTE' DESC, d.requested_at DESC
             LIMIT :limit
            """;

    private static final String BY_ID = COLUMNS + """
             WHERE d.id = :id
            """;

    private static final String RESOLVE = """
            UPDATE portal_deactivation_request
               SET status           = :status,
                   approver_id      = :approverId,
                   approver_comment = :comment,
                   resolved_at      = :resolvedAt,
                   executed_at      = :executedAt
             WHERE id     = :id
               AND status = 'PENDIENTE'
            """;

    private final JdbcClient jdbc;

    public DeactivationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasPendingFor(long registrationId) {
        Integer count = jdbc.sql(PENDING_FOR_REGISTRATION)
                .param("registrationId", registrationId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    public void create(long registrationId, String scope, int bankId, long requesterId,
            String reason, Instant requestedAt, Instant expiresAt) {
        jdbc.sql(INSERT)
                .param("registrationId", registrationId)
                .param("scope", scope)
                .param("bankId", bankId)
                .param("requesterId", requesterId)
                .param("reason", reason)
                .param("requestedAt", Timestamp.from(requestedAt))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
    }

    public List<Row> byBank(int bankId, int limit) {
        return jdbc.sql(BY_BANK)
                .param("bankId", bankId)
                .param("limit", limit)
                .query(Row.MAPPER)
                .list();
    }

    public Optional<Row> byId(long id) {
        return jdbc.sql(BY_ID).param("id", id).query(Row.MAPPER).optional();
    }

    /**
     * Cierra la solicitud, y sólo si sigue PENDIENTE.
     *
     * <p>El {@code AND status = 'PENDIENTE'} del UPDATE es lo que evita que dos
     * aprobadores que abren la bandeja a la vez resuelvan la misma solicitud dos
     * veces: el segundo actualiza cero filas y se entera por el retorno, en vez
     * de ejecutar una baja sobre un registro que ya fue dado de baja.
     *
     * @return true si esta llamada fue la que la cerró
     */
    public boolean resolve(long id, String status, long approverId, String comment,
            Instant resolvedAt, Instant executedAt) {
        int updated = jdbc.sql(RESOLVE)
                .param("id", id)
                .param("status", status)
                .param("approverId", approverId)
                .param("comment", comment)
                .param("resolvedAt", Timestamp.from(resolvedAt))
                .param("executedAt", executedAt == null ? null : Timestamp.from(executedAt))
                .update();
        return updated == 1;
    }

    public record Row(long id, long registrationId, String aliasUuid, String aliasEnc, int bankId,
            String bic, String bankName, String status, String reason, long requesterId,
            String requestedBy, Timestamp requestedAt, String resolvedBy, Timestamp resolvedAt,
            String approverComment, Timestamp expiresAt) {

        static final org.springframework.jdbc.core.RowMapper<Row> MAPPER = (rs, row) -> new Row(
                rs.getLong("id"),
                rs.getLong("registration_id"),
                rs.getString("alias_uuid"),
                text(rs.getBytes("alias_enc")),
                rs.getInt("bank_id"),
                rs.getString("bic"),
                rs.getString("bank_name"),
                rs.getString("status"),
                rs.getString("reason"),
                rs.getLong("requester_id"),
                rs.getString("requested_by"),
                rs.getTimestamp("requested_at"),
                rs.getString("resolved_by"),
                rs.getTimestamp("resolved_at"),
                rs.getString("approver_comment"),
                rs.getTimestamp("expires_at"));
    }

    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
