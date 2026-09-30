package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resuelve al operador por el {@code sub} de su token y trae sus permisos.
 *
 * <p>Dos consultas y no un JOIN con GROUP_CONCAT: los permisos son una lista y
 * pegarlos en una cadena obligaría a volver a partirla aquí, con el riesgo de
 * que un código con coma rompa el parseo silenciosamente.
 */
@Repository
public class PortalUserRepository {

    private static final String BY_SUBJECT = """
            SELECT u.id          AS id,
                   u.bank_id     AS bank_id,
                   b.bicfi       AS bic,
                   u.username    AS username,
                   u.full_name   AS full_name
              FROM portal_user      u
              LEFT JOIN participant_bank b ON b.id = u.bank_id
             WHERE u.idp_subject = :subject
               AND u.status      = 'ACTIVO'
            """;

    private static final String PERMISSIONS_OF_USER = """
            SELECT DISTINCT p.code AS code
              FROM portal_user_role       ur
              JOIN portal_role            r  ON r.id = ur.role_id AND r.active = 1
              JOIN portal_role_permission rp ON rp.role_id = r.id
              JOIN portal_permission      p  ON p.id = rp.permission_id
             WHERE ur.user_id = :userId
            """;

    private static final String TOUCH_LAST_ACCESS = """
            UPDATE portal_user SET last_access_at = CURRENT_TIMESTAMP(3) WHERE id = :userId
            """;

    private final JdbcClient jdbc;

    public PortalUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PortalUser> bySubject(String subject) {
        var found = jdbc.sql(BY_SUBJECT)
                .param("subject", subject)
                .query((rs, row) -> new Header(
                        rs.getLong("id"),
                        rs.getObject("bank_id") == null ? null : rs.getInt("bank_id"),
                        rs.getString("bic"),
                        rs.getString("username"),
                        rs.getString("full_name")))
                .optional();

        return found.map(h -> new PortalUser(h.id(), h.bankId(), h.bic(), h.username(),
                h.fullName(), permissionsOf(h.id())));
    }

    public void touchLastAccess(long userId) {
        jdbc.sql(TOUCH_LAST_ACCESS).param("userId", userId).update();
    }

    private Set<String> permissionsOf(long userId) {
        List<String> codes = jdbc.sql(PERMISSIONS_OF_USER)
                .param("userId", userId)
                .query(String.class)
                .list();
        return Set.copyOf(codes);
    }

    private record Header(long id, Integer bankId, String bic, String username, String fullName) {
    }
}
