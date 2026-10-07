package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Escribe y consulta {@code portal_audit_log}.
 *
 * <p>La tabla está particionada por {@code part_date} y tiene un CHECK que
 * obliga a que coincida con la fecha de {@code occurred_at}. Las dos se calculan
 * aquí del mismo instante para que no puedan discrepar por un cambio de día
 * entre una línea y la siguiente.
 *
 * <p>La IP se guarda con {@code INET6_ATON} en VARBINARY(16), que acepta IPv4 e
 * IPv6 con el mismo tipo y ocupa la mitad que el texto. Al leer se devuelve con
 * {@code INET6_NTOA}.
 */
@Repository
public class AuditRepository {

    private static final String INSERT = """
            INSERT INTO portal_audit_log
                   (part_date, occurred_at, user_id, user_login, bank_id, bank_bicfi,
                    ip, user_agent, operation, entity, entity_ref, result, http_status,
                    detail, correlation_id)
            VALUES (:partDate, :occurredAt, :userId, :userLogin, :bankId, :bankBicfi,
                    INET6_ATON(:ip), :userAgent, :operation, :entity, :entityRef, :result,
                    :httpStatus, :detail, :correlationId)
            """;

    private static final String SEARCH_HEAD = """
            SELECT occurred_at          AS occurred_at,
                   user_login           AS user_login,
                   bank_bicfi           AS bank_bicfi,
                   INET6_NTOA(ip)       AS ip,
                   operation            AS operation,
                   entity               AS entity,
                   entity_ref           AS entity_ref,
                   result               AS result,
                   http_status          AS http_status,
                   detail               AS detail,
                   correlation_id       AS correlation_id
              FROM portal_audit_log
             WHERE part_date BETWEEN :from AND :to
            """;

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void write(AuditRow row) {
        jdbc.sql(INSERT)
                .param("partDate", row.occurredAt().toLocalDate())
                .param("occurredAt", row.occurredAt())
                .param("userId", row.userId())
                .param("userLogin", row.userLogin())
                .param("bankId", row.bankId())
                .param("bankBicfi", row.bankBicfi())
                .param("ip", row.ip())
                .param("userAgent", row.userAgent())
                .param("operation", row.operation())
                .param("entity", row.entity())
                .param("entityRef", row.entityRef())
                .param("result", row.result().name())
                .param("httpStatus", row.httpStatus())
                .param("detail", row.detailJson())
                .param("correlationId", row.correlationId())
                .update();
    }

    /**
     * Consulta con los filtros de PTRAD-787: rango de fechas, usuario y tipo de
     * operación.
     *
     * <p>{@code bankId} no es un filtro opcional del usuario sino el alcance que
     * impone {@link AuditService}: un administrador de entidad sólo ve su propia
     * bitácora. Cuando llega null, es porque el llamador tiene
     * BITACORA_CONSULTAR_GLOBAL.
     *
     * <p>El rango se aplica sobre {@code part_date} para que el motor pode
     * particiones; sin eso, un filtro por {@code occurred_at} obliga a recorrer
     * todas.
     */
    public List<PortalDto.AuditView> search(LocalDate from, LocalDate to, Integer bankId,
            String userLogin, String operation, int limit) {
        var sql = new StringBuilder(SEARCH_HEAD);
        if (bankId != null) {
            sql.append(" AND bank_id = :bankId\n");
        }
        if (userLogin != null && !userLogin.isBlank()) {
            sql.append(" AND user_login = :userLogin\n");
        }
        if (operation != null && !operation.isBlank()) {
            sql.append(" AND operation = :operation\n");
        }
        sql.append(" ORDER BY occurred_at DESC\n LIMIT :limit");

        var statement = jdbc.sql(sql.toString())
                .param("from", from)
                .param("to", to)
                .param("limit", limit);
        if (bankId != null) {
            statement = statement.param("bankId", bankId);
        }
        if (userLogin != null && !userLogin.isBlank()) {
            statement = statement.param("userLogin", userLogin);
        }
        if (operation != null && !operation.isBlank()) {
            statement = statement.param("operation", operation);
        }

        return statement.query((rs, row) -> new PortalDto.AuditView(
                rs.getTimestamp("occurred_at").toLocalDateTime(),
                rs.getString("user_login"),
                rs.getString("bank_bicfi"),
                rs.getString("ip"),
                rs.getString("operation"),
                rs.getString("entity"),
                rs.getString("entity_ref"),
                rs.getString("result"),
                rs.getObject("http_status") == null ? null : rs.getInt("http_status"),
                rs.getString("detail"),
                rs.getString("correlation_id"))).list();
    }

    /** Catálogo para el combo de filtros, tomado de lo que realmente hay escrito. */
    public List<String> distinctOperations(LocalDate from, LocalDate to, Integer bankId) {
        var sql = new StringBuilder("""
                SELECT DISTINCT operation AS operation
                  FROM portal_audit_log
                 WHERE part_date BETWEEN :from AND :to
                """);
        if (bankId != null) {
            sql.append(" AND bank_id = :bankId\n");
        }
        sql.append(" ORDER BY operation");

        var statement = jdbc.sql(sql.toString()).param("from", from).param("to", to);
        if (bankId != null) {
            statement = statement.param("bankId", bankId);
        }
        return new ArrayList<>(statement.query(String.class).list());
    }

    /** Fila ya resuelta y lista para escribir. */
    public record AuditRow(LocalDateTime occurredAt, Long userId, String userLogin, Integer bankId,
            String bankBicfi, String ip, String userAgent, String operation, String entity,
            String entityRef, AuditResult result, Integer httpStatus, String detailJson,
            String correlationId) {
    }
}
