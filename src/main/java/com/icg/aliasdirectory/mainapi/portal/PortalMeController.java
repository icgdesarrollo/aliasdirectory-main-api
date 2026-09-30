package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Quién es el operador y qué puede hacer.
 *
 * <p>El portal pinta su menú con esto y no con los roles del token: la
 * autorización vive en el padrón (Anexo §8.5), y si el navegador la dedujera del
 * token mostraría opciones que el BFF después rechaza, o escondería otras que sí
 * tiene.
 *
 * <p>No descifra nada, así que —a diferencia del resto del portal— existe
 * también con el KMS apagado. Sirve para que un ambiente sin KMS pueda al menos
 * comprobar que la autenticación quedó bien conectada.
 */
@ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "true")
@RestController
@RequestMapping("/portal")
public class PortalMeController {

    private final CurrentPortalUser currentUser;
    private final PortalUserRepository users;

    public PortalMeController(CurrentPortalUser currentUser, PortalUserRepository users) {
        this.currentUser = currentUser;
        this.users = users;
    }

    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.Me me() {
        var user = currentUser.require();
        users.touchLastAccess(user.id());
        return new PortalDto.Me(user.username(), user.fullName(), user.bic(),
                user.permissions());
    }
}
