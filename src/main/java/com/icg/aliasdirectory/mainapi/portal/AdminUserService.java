package com.icg.aliasdirectory.mainapi.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Administración delegada de agentes por banco (E15-D04).
 *
 * <p>Dos reglas gobiernan todo lo de aquí:
 *
 * <ol>
 *   <li><b>La entidad del agente creado es la del administrador</b>, tomada del
 *       padrón vía el token (regla T-1). No hay forma de dar de alta a alguien
 *       en otra entidad, porque el banco nunca llega en el cuerpo.</li>
 *   <li><b>Un administrador de entidad sólo ve y toca a los suyos.</b> El
 *       alcance se aplica en el WHERE de cada consulta, no filtrando después.
 *       Un administrador de ICG trabaja con alcance global.</li>
 * </ol>
 *
 * <p>El alta toca dos sistemas: Keycloak y el padrón. El orden es primero el
 * IdP y después la base, dentro de una transacción. Si la base falla, el usuario
 * queda creado en Keycloak pero deshabilitado y sin alta en el padrón, con lo
 * que no puede entrar a ningún lado; es un huérfano visible y limpiable. El
 * orden inverso dejaría un alta en el padrón apuntando a un {@code sub} que no
 * existe, que sí es un estado inconsistente y silencioso.
 */
@ConditionalOnProperty(name = {"icg.portal.enabled", "icg.portal.keycloak.enabled"},
        havingValue = "true")
@Service
public class AdminUserService {

    private static final Set<String> VALID_STATUS = Set.of("ACTIVO", "INACTIVO", "BLOQUEADO");

    // Sin caracteres ambiguos: esta contraseña se dicta o se copia a mano, y la
    // diferencia entre O y 0 o entre l y 1 se pierde al leerla en voz alta.
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnopqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "@#$%&*+-";
    private static final int PASSWORD_LENGTH = 16;

    private final AdminUserRepository repository;
    private final KeycloakAdminClient idp;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public AdminUserService(AdminUserRepository repository, KeycloakAdminClient idp,
            AuditService audit) {
        this.repository = repository;
        this.idp = idp;
        this.audit = audit;
    }

    public List<PortalDto.PortalUserView> list(PortalUser admin) {
        return repository.listByBank(scopeOf(admin));
    }

    public List<PortalDto.RoleView> roles(PortalUser admin) {
        return repository.assignableRoles(isGlobalAdmin(admin));
    }

    @Transactional
    public PortalDto.UserCreatedView create(PortalDto.UserCreateInput input, PortalUser admin,
            HttpServletRequest request) {
        if (!admin.belongsToBank()) {
            // Un administrador de ICG no tiene entidad propia; el alta de
            // agentes de un banco la hace ese banco.
            throw new PortalException(400, "ICG-400-SIN-ENTIDAD",
                    "El alta de agentes la realiza el administrador de cada entidad.");
        }
        String username = required(input.username(), "nombre de usuario").trim().toLowerCase();
        String fullName = required(input.fullName(), "nombre completo").trim();
        String email = required(input.email(), "correo").trim();

        if (repository.usernameTaken(username)) {
            throw new PortalException(409, "ICG-409-USUARIO-EXISTE",
                    "Ya existe un agente con ese nombre de usuario.");
        }

        String temporary = generatePassword();
        String[] parts = splitName(fullName);
        String subject = idp.createUser(new KeycloakAdminClient.NewUser(
                username, email, parts[0], parts[1], temporary));

        idp.groupIdByBic(admin.bic()).ifPresent(groupId -> idp.addToGroup(subject, groupId));

        long id = repository.insert(admin.bankId(), subject, username, fullName, email,
                admin.id());
        var assigned = repository.replaceRoles(id, safeRoles(input.roles()), admin.id(),
                isGlobalAdmin(admin));

        audit.record(AuditEntry.ok(AuditOperation.USER_CREATE, "USUARIO", String.valueOf(id),
                Map.of("usuario", username, "roles", String.join(",", assigned))),
                admin, request);

        return new PortalDto.UserCreatedView(id, username, temporary);
    }

    @Transactional
    public PortalDto.PortalUserView update(long id, PortalDto.UserUpdateInput input,
            PortalUser admin, HttpServletRequest request) {
        var target = target(id, admin);
        String fullName = required(input.fullName(), "nombre completo").trim();
        String email = required(input.email(), "correo").trim();

        repository.updateProfile(id, fullName, email);
        String[] parts = splitName(fullName);
        idp.updateUser(target.idpSubject(), email, parts[0], parts[1]);

        audit.record(AuditEntry.ok(AuditOperation.USER_UPDATE, "USUARIO", String.valueOf(id),
                Map.of("usuario", target.username())), admin, request);
        return single(id, admin);
    }

    /**
     * Activa, inactiva o bloquea.
     *
     * <p>El cambio se propaga al IdP y, al desactivar, se cierran las sesiones
     * abiertas. Sin eso, el agente seguiría operando con el token que ya tenía
     * hasta que expirara, que es justo la ventana que un bloqueo busca cerrar.
     */
    @Transactional
    public PortalDto.PortalUserView changeStatus(long id, PortalDto.UserStatusInput input,
            PortalUser admin, HttpServletRequest request) {
        var target = target(id, admin);
        String status = required(input.status(), "estado").trim().toUpperCase();
        if (!VALID_STATUS.contains(status)) {
            throw new PortalException(400, "ICG-400-ESTADO-INVALIDO",
                    "El estado debe ser ACTIVO, INACTIVO o BLOQUEADO.");
        }
        if (target.id() == admin.id()) {
            // Un administrador que se desactiva a sí mismo deja la entidad sin
            // quien administre.
            throw new PortalException(400, "ICG-400-AUTO-CAMBIO",
                    "No puede cambiar el estado de su propio usuario.");
        }

        repository.updateStatus(id, status);
        boolean enabled = "ACTIVO".equals(status);
        idp.setEnabled(target.idpSubject(), enabled);
        if (!enabled) {
            idp.logoutUser(target.idpSubject());
        }

        audit.record(AuditEntry.ok(AuditOperation.USER_STATUS, "USUARIO", String.valueOf(id),
                Map.of("usuario", target.username(), "estado", status,
                        "motivo", input.reason() == null ? "" : input.reason())),
                admin, request);
        return single(id, admin);
    }

    @Transactional
    public PortalDto.PortalUserView assignRoles(long id, PortalDto.UserRolesInput input,
            PortalUser admin, HttpServletRequest request) {
        CurrentPortalUser.requiring(admin, PortalPermission.ROLE_ASSIGN);
        var target = target(id, admin);
        var before = repository.rolesOf(id);
        var after = repository.replaceRoles(id, safeRoles(input.roles()), admin.id(),
                isGlobalAdmin(admin));

        audit.record(AuditEntry.ok(
                after.containsAll(before) ? AuditOperation.ROLE_ASSIGN
                        : AuditOperation.ROLE_REVOKE,
                "USUARIO", String.valueOf(id),
                Map.of("usuario", target.username(),
                        "antes", String.join(",", before),
                        "despues", String.join(",", after))), admin, request);
        return single(id, admin);
    }

    // ---- apoyo -----------------------------------------------------------

    private AdminUserRepository.Target target(long id, PortalUser admin) {
        return repository.byIdInBank(id, scopeOf(admin))
                .orElseThrow(() -> new PortalException(404, "ICG-404-USUARIO",
                    "El agente no existe o no pertenece a su entidad."));
    }

    private PortalDto.PortalUserView single(long id, PortalUser admin) {
        return repository.listByBank(scopeOf(admin)).stream()
                .filter(view -> view.id() == id)
                .findFirst()
                .orElseThrow(() -> new PortalException(404, "ICG-404-USUARIO",
                    "El agente no existe o no pertenece a su entidad."));
    }

    /** null significa alcance global; cualquier otro valor acota al banco. */
    private Integer scopeOf(PortalUser admin) {
        return isGlobalAdmin(admin) ? null : admin.bankId();
    }

    private boolean isGlobalAdmin(PortalUser admin) {
        return !admin.belongsToBank() && admin.can(PortalPermission.BANK_ADMIN);
    }

    private List<String> safeRoles(List<String> roles) {
        return roles == null ? List.of() : roles.stream().filter(r -> r != null && !r.isBlank())
                .map(String::trim).distinct().toList();
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PortalException(400, "ICG-400-CAMPO-REQUERIDO",
                    "Falta el campo obligatorio: " + field + ".");
        }
        return value;
    }

    /** Keycloak guarda nombre y apellido por separado; el padrón, uno solo. */
    private String[] splitName(String fullName) {
        int space = fullName.indexOf(' ');
        return space < 0 ? new String[] {fullName, ""}
                : new String[] {fullName.substring(0, space), fullName.substring(space + 1)};
    }

    /**
     * Contraseña temporal que cumple la política del reino por construcción.
     *
     * <p>Lleva al menos una mayúscula, una minúscula, un dígito y un símbolo
     * porque una cadena puramente aleatoria puede no traerlos: con 16
     * caracteres es improbable, pero «improbable» significa que el alta falla
     * de vez en cuando con un 400 del IdP que nadie sabría explicar. Las cuatro
     * posiciones obligatorias se barajan para que su lugar no sea predecible.
     */
    private String generatePassword() {
        String all = UPPER + LOWER + DIGITS + SYMBOLS;
        var characters = new ArrayList<Character>(PASSWORD_LENGTH);
        characters.add(UPPER.charAt(random.nextInt(UPPER.length())));
        characters.add(LOWER.charAt(random.nextInt(LOWER.length())));
        characters.add(DIGITS.charAt(random.nextInt(DIGITS.length())));
        characters.add(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        while (characters.size() < PASSWORD_LENGTH) {
            characters.add(all.charAt(random.nextInt(all.length())));
        }
        Collections.shuffle(characters, random);

        var builder = new StringBuilder(PASSWORD_LENGTH);
        characters.forEach(builder::append);
        return builder.toString();
    }
}
