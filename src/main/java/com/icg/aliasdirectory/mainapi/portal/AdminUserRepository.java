package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Padrón de agentes: {@code portal_user} y {@code portal_user_role}.
 *
 * <p>Todas las consultas llevan el {@code bank_id} en el WHERE y no sólo en el
 * SELECT. El alcance de un administrador de entidad se aplica en la consulta,
 * no filtrando en Java lo que ya vino de más: un filtro que se olvida en una
 * rama deja ver el padrón de otro banco.
 */
@Repository
public class AdminUserRepository {

    private static final String LIST_BY_BANK = """
            SELECT u.id            AS id,
                   u.username      AS username,
                   u.full_name     AS full_name,
                   u.email         AS email,
                   b.bicfi         AS bic,
                   u.status        AS status,
                   u.enrolled_at   AS enrolled_at,
                   u.last_access_at AS last_access_at,
                   c.username      AS created_by
              FROM portal_user u
              LEFT JOIN participant_bank b ON b.id = u.bank_id
              LEFT JOIN portal_user      c ON c.id = u.created_by
             WHERE (:bankId IS NULL OR u.bank_id = :bankId)
             ORDER BY u.username
            """;

    private static final String BY_ID_IN_BANK = """
            SELECT u.id            AS id,
                   u.username      AS username,
                   u.full_name     AS full_name,
                   u.email         AS email,
                   u.idp_subject   AS idp_subject,
                   u.bank_id       AS bank_id,
                   b.bicfi         AS bic,
                   u.status        AS status
              FROM portal_user u
              LEFT JOIN participant_bank b ON b.id = u.bank_id
             WHERE u.id = :id
               AND (:bankId IS NULL OR u.bank_id = :bankId)
            """;

    private static final String INSERT = """
            INSERT INTO portal_user (bank_id, idp_subject, username, full_name, email, status,
                                     created_by)
            VALUES (:bankId, :idpSubject, :username, :fullName, :email, 'ACTIVO', :createdBy)
            """;

    private static final String UPDATE_PROFILE = """
            UPDATE portal_user SET full_name = :fullName, email = :email WHERE id = :id
            """;

    private static final String UPDATE_STATUS = """
            UPDATE portal_user SET status = :status WHERE id = :id
            """;

    private static final String ROLES_OF_USER = """
            SELECT r.code AS code
              FROM portal_user_role ur
              JOIN portal_role      r ON r.id = ur.role_id
             WHERE ur.user_id = :userId
             ORDER BY r.code
            """;

    private static final String ASSIGNABLE_ROLES = """
            SELECT code AS code, name AS name, description AS description, scope AS scope
              FROM portal_role
             WHERE active = 1
               AND (:includeIcg = TRUE OR scope = 'BANCO')
             ORDER BY code
            """;

    private static final String DELETE_ROLES = """
            DELETE FROM portal_user_role WHERE user_id = :userId
            """;

    /**
     * Inserta sólo los roles que el catálogo admite para el alcance pedido. Un
     * código inexistente o de alcance ICG simplemente no inserta fila, en vez de
     * fallar con una violación de clave ajena que no explicaría nada.
     */
    private static final String INSERT_ROLE = """
            INSERT INTO portal_user_role (user_id, role_id, assigned_by)
            SELECT :userId, r.id, :assignedBy
              FROM portal_role r
             WHERE r.code = :code
               AND r.active = 1
               AND (:includeIcg = TRUE OR r.scope = 'BANCO')
            """;

    private static final String EXISTS_USERNAME = """
            SELECT COUNT(*) FROM portal_user WHERE username = :username
            """;

    private final JdbcClient jdbc;

    public AdminUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<PortalDto.PortalUserView> listByBank(Integer bankId) {
        return jdbc.sql(LIST_BY_BANK)
                .param("bankId", bankId)
                .query((rs, row) -> new PortalDto.PortalUserView(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("bic"),
                        rs.getString("status"),
                        String.valueOf(rs.getTimestamp("enrolled_at").toLocalDateTime()),
                        rs.getTimestamp("last_access_at") == null ? null
                                : String.valueOf(rs.getTimestamp("last_access_at")
                                        .toLocalDateTime()),
                        rs.getString("created_by"),
                        rolesOf(rs.getLong("id"))))
                .list();
    }

    public Optional<Target> byIdInBank(long id, Integer bankId) {
        return jdbc.sql(BY_ID_IN_BANK)
                .param("id", id)
                .param("bankId", bankId)
                .query((rs, row) -> new Target(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("idp_subject"),
                        rs.getObject("bank_id") == null ? null : rs.getInt("bank_id"),
                        rs.getString("bic"),
                        rs.getString("status")))
                .optional();
    }

    public boolean usernameTaken(String username) {
        Integer count = jdbc.sql(EXISTS_USERNAME).param("username", username)
                .query(Integer.class).single();
        return count != null && count > 0;
    }

    public long insert(Integer bankId, String idpSubject, String username, String fullName,
            String email, long createdBy) {
        var keys = new GeneratedKeyHolder();
        jdbc.sql(INSERT)
                .param("bankId", bankId)
                .param("idpSubject", idpSubject)
                .param("username", username)
                .param("fullName", fullName)
                .param("email", email)
                .param("createdBy", createdBy)
                .update(keys);
        var key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("El alta no devolvió el id generado");
        }
        return key.longValue();
    }

    public void updateProfile(long id, String fullName, String email) {
        jdbc.sql(UPDATE_PROFILE).param("id", id).param("fullName", fullName)
                .param("email", email).update();
    }

    public void updateStatus(long id, String status) {
        jdbc.sql(UPDATE_STATUS).param("id", id).param("status", status).update();
    }

    public List<String> rolesOf(long userId) {
        return jdbc.sql(ROLES_OF_USER).param("userId", userId).query(String.class).list();
    }

    public List<PortalDto.RoleView> assignableRoles(boolean includeIcg) {
        return jdbc.sql(ASSIGNABLE_ROLES)
                .param("includeIcg", includeIcg)
                .query((rs, row) -> new PortalDto.RoleView(
                        rs.getString("code"), rs.getString("name"),
                        rs.getString("description"), rs.getString("scope")))
                .list();
    }

    /** Reemplaza el juego de roles completo; devuelve los que quedaron. */
    public List<String> replaceRoles(long userId, List<String> codes, long assignedBy,
            boolean includeIcg) {
        jdbc.sql(DELETE_ROLES).param("userId", userId).update();
        for (String code : codes) {
            jdbc.sql(INSERT_ROLE)
                    .param("userId", userId)
                    .param("assignedBy", assignedBy)
                    .param("code", code)
                    .param("includeIcg", includeIcg)
                    .update();
        }
        return rolesOf(userId);
    }

    /** El agente sobre el que se va a actuar, ya acotado al alcance permitido. */
    public record Target(long id, String username, String fullName, String email,
            String idpSubject, Integer bankId, String bic, String status) {
    }
}
