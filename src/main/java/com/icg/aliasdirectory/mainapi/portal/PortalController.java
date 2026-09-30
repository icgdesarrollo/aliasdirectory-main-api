package com.icg.aliasdirectory.mainapi.portal;

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
 * El BFF del portal de Call Center (E15).
 *
 * <p>Habla JSON, no ISO 20022. Los bancos entran por Dispatch con acmt.023 y
 * icg.001 porque el RFP §2 lo exige de ellos; un portal propio no tiene por qué
 * armar XML en el navegador, y hacerlo pondría la lógica del esquema —y la
 * cabecera con el BIC— donde el cliente puede cambiarla.
 *
 * <p>La entidad del operador sale del token y del padrón, nunca del cuerpo ni de
 * una cabecera (regla T-1). Cada endpoint exige además el permiso que le toca:
 * el portal esconde las opciones que el usuario no tiene, pero esconder no es
 * controlar.
 *
 * <p>Descifra, así que necesita KMS y no existe sin él. {@link PortalMeController}
 * sí existe siempre.
 */
@ConditionalOnProperty(name = {"icg.kms.enabled", "icg.portal.enabled"}, havingValue = "true")
@RestController
@RequestMapping("/portal")
public class PortalController {

    private final CurrentPortalUser currentUser;
    private final PortalAliasService aliases;
    private final DeactivationService deactivations;

    public PortalController(CurrentPortalUser currentUser, PortalAliasService aliases,
            DeactivationService deactivations) {
        this.currentUser = currentUser;
        this.aliases = aliases;
        this.deactivations = deactivations;
    }

    /**
     * El alias viaja en la ruta y no en una cadena de consulta: las cadenas de
     * consulta quedan en los registros del proxy, en el historial del navegador
     * y en la cabecera Referer, y un alias es el teléfono de una persona.
     */
    @GetMapping(path = "/alias/{alias}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.AliasLookup lookup(@PathVariable String alias) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.QUERY_BY_ALIAS);
        return aliases.lookup(alias, user);
    }

    @GetMapping(path = "/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalDto.CancellationView> inbox() {
        var user = currentUser.require();
        // La bandeja la ve tanto quien captura como quien aprueba: el que
        // solicitó necesita saber en qué quedó su solicitud.
        if (!user.can(PortalPermission.CANCEL_REQUEST)
                && !user.can(PortalPermission.CANCEL_APPROVE)) {
            throw new CurrentPortalUser.MissingPermissionException(PortalPermission.CANCEL_REQUEST);
        }
        return deactivations.inbox(user);
    }

    @PostMapping(path = "/cancellations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void request(@RequestBody PortalDto.CancellationRequestInput input) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_REQUEST);
        deactivations.request(input, user);
    }

    @PostMapping(path = "/cancellations/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable long id) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_APPROVE);
        deactivations.approve(id, user);
    }

    @PostMapping(path = "/cancellations/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable long id,
            @RequestBody PortalDto.CancellationRejectInput input) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_APPROVE);
        deactivations.reject(id, input, user);
    }
}
