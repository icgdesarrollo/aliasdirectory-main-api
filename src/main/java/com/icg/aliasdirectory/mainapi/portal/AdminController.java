package com.icg.aliasdirectory.mainapi.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administración delegada de agentes y roles por banco (E15-D04).
 *
 * <p>Todo pasa por {@code USUARIO_ADMINISTRAR}, que sólo tienen ADMIN_BANCO y
 * ADMIN_ICG. La entidad sobre la que se actúa sale del padrón del administrador
 * autenticado y nunca del cuerpo ni de la ruta.
 *
 * <p>Se usa POST y no PUT/PATCH para los cambios de estado y de roles porque
 * ambos disparan efectos en el IdP —deshabilitar, cerrar sesiones— que no son
 * idempotentes en el sentido que PUT promete.
 */
@ConditionalOnProperty(name = {"icg.portal.enabled", "icg.portal.keycloak.enabled"},
        havingValue = "true")
@RestController
@RequestMapping("/portal/admin")
public class AdminController {

    private final CurrentPortalUser currentUser;
    private final AdminUserService users;

    public AdminController(CurrentPortalUser currentUser, AdminUserService users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @GetMapping(path = "/users", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalDto.PortalUserView> list() {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.list(admin);
    }

    @GetMapping(path = "/roles", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalDto.RoleView> roles() {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.roles(admin);
    }

    @PostMapping(path = "/users", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public PortalDto.UserCreatedView create(@RequestBody PortalDto.UserCreateInput input,
            HttpServletRequest request) {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.create(input, admin, request);
    }

    @PostMapping(path = "/users/{id}", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.PortalUserView update(@PathVariable long id,
            @RequestBody PortalDto.UserUpdateInput input, HttpServletRequest request) {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.update(id, input, admin, request);
    }

    @PostMapping(path = "/users/{id}/status", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.PortalUserView changeStatus(@PathVariable long id,
            @RequestBody PortalDto.UserStatusInput input, HttpServletRequest request) {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.changeStatus(id, input, admin, request);
    }

    @PostMapping(path = "/users/{id}/roles", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.PortalUserView assignRoles(@PathVariable long id,
            @RequestBody PortalDto.UserRolesInput input, HttpServletRequest request) {
        var admin = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.USER_ADMIN);
        return users.assignRoles(id, input, admin, request);
    }
}
