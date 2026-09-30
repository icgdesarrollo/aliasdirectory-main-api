package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * El operador de la petición en curso.
 *
 * <p>Sale del {@code sub} del token y de {@code portal_user}, nunca de un
 * parámetro ni de una cabecera: la entidad a nombre de la que actúa un agente es
 * un hecho de la autenticación y no una entrada (regla T-1). Un token válido de
 * alguien que no está dado de alta en el portal no alcanza ningún endpoint.
 */
@Component
public class CurrentPortalUser {

    private final PortalUserRepository users;

    public CurrentPortalUser(PortalUserRepository users) {
        this.users = users;
    }

    public PortalUser require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            throw new NotEnrolledException("La petición no trae un token válido");
        }
        Jwt jwt = jwtAuthentication.getToken();
        String subject = jwt.getSubject();

        return users.bySubject(subject).orElseThrow(() -> new NotEnrolledException(
                "El token es válido pero su sub no corresponde a un usuario activo del portal"));
    }

    /** Autenticado contra el IdP pero sin alta en el portal, o dado de baja. */
    public static class NotEnrolledException extends RuntimeException {
        public NotEnrolledException(String message) {
            super(message);
        }
    }

    /** Tiene alta pero le falta el permiso que el endpoint exige. */
    public static class MissingPermissionException extends RuntimeException {
        public MissingPermissionException(String permission) {
            super("El usuario no tiene el permiso " + permission);
        }
    }

    public static PortalUser requiring(PortalUser user, String permission) {
        if (!user.can(permission)) {
            throw new MissingPermissionException(permission);
        }
        return user;
    }
}
