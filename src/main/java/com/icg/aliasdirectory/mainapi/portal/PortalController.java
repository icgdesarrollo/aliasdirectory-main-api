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
import java.util.Map;

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
 * <p><b>Los valores consultados viajan en la ruta y nunca en una cadena de
 * consulta.</b> Las cadenas de consulta quedan en los registros del proxy, en el
 * historial del navegador y en la cabecera Referer, y aquí cada valor es el
 * teléfono, la cuenta o el DPI de una persona.
 *
 * <p><b>Toda operación queda en la bitácora</b> (Anexo §8.4). Lo que se registra
 * es el criterio usado y cuántos registros devolvió, nunca el valor consultado:
 * saber que el agente X buscó por DPI a las 10:04 y obtuvo dos resultados es
 * auditoría; guardar el DPI sería mover el dato sensible a una segunda tabla.
 */
@ConditionalOnProperty(name = {"icg.kms.enabled", "icg.portal.enabled"}, havingValue = "true")
@RestController
@RequestMapping("/portal")
public class PortalController {

    private final CurrentPortalUser currentUser;
    private final PortalAliasService aliases;
    private final DeactivationService deactivations;
    private final AuditService audit;

    public PortalController(CurrentPortalUser currentUser, PortalAliasService aliases,
            DeactivationService deactivations, AuditService audit) {
        this.currentUser = currentUser;
        this.aliases = aliases;
        this.deactivations = deactivations;
        this.audit = audit;
    }

    // ---- consultas (Anexo §8.1) -------------------------------------------

    @GetMapping(path = "/alias/{alias}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.AliasLookup byAlias(@PathVariable String alias, HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.QUERY_BY_ALIAS);
        return audited(aliases.byAlias(alias, user), "ALIAS", user, request);
    }

    @GetMapping(path = "/account/{iban}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.AliasLookup byAccount(@PathVariable String iban, HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.QUERY_BY_ACCOUNT);
        return audited(aliases.byAccount(iban, user), "CUENTA", user, request);
    }

    @GetMapping(path = "/holder/{dpi}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.AliasLookup byHolder(@PathVariable String dpi, HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.QUERY_BY_DPI);
        return audited(aliases.byHolder(dpi, user), "DPI", user, request);
    }

    @GetMapping(path = "/customer/{customerId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public PortalDto.AliasLookup byCustomer(@PathVariable String customerId,
            HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.QUERY_BY_CUSTOMER);
        return audited(aliases.byCustomer(customerId, user), "CLIENTE", user, request);
    }

    // ---- bajas con doble control (Anexo §8.2) -----------------------------

    @GetMapping(path = "/cancellations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalDto.CancellationView> inbox() {
        var user = currentUser.require();
        // La bandeja la ve tanto quien captura como quien aprueba: el que
        // solicitó necesita saber en qué quedó su solicitud.
        if (!user.can(PortalPermission.CANCEL_REQUEST)
                && !user.can(PortalPermission.CANCEL_APPROVE)) {
            throw new CurrentPortalUser.MissingPermissionException(PortalPermission.CANCEL_REQUEST);
        }
        // La bandeja no se audita: es la pantalla de trabajo del agente y
        // registrarla una vez por refresco ahogaría la bitácora sin aportar nada
        // que no esté ya en el registro de cada solicitud.
        return deactivations.inbox(user);
    }

    @PostMapping(path = "/cancellations", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public void request(@RequestBody PortalDto.CancellationRequestInput input,
            HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_REQUEST);
        deactivations.request(input, user);
        audit.record(AuditEntry.ok(AuditOperation.CANCEL_REQUEST, "ALIAS", input.aliasUuid()),
                user, request);
    }

    @PostMapping(path = "/cancellations/{id}/approve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable long id, HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_APPROVE);
        deactivations.approve(id, user);
        audit.record(AuditEntry.ok(AuditOperation.CANCEL_APPROVE, "SOLICITUD",
                String.valueOf(id)), user, request);
    }

    @PostMapping(path = "/cancellations/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable long id,
            @RequestBody PortalDto.CancellationRejectInput input, HttpServletRequest request) {
        var user = CurrentPortalUser.requiring(currentUser.require(),
                PortalPermission.CANCEL_APPROVE);
        deactivations.reject(id, input, user);
        audit.record(AuditEntry.ok(AuditOperation.CANCEL_REJECT, "SOLICITUD",
                String.valueOf(id)), user, request);
    }

    /**
     * Registra la consulta y devuelve su resultado sin tocarlo.
     *
     * <p>{@code entityRef} lleva el AliasUUID cuando hubo exactamente un
     * resultado, que es el caso en que la línea de bitácora puede señalar un
     * registro concreto. Con varios, o con ninguno, queda nulo: inventar una
     * referencia que no identifica nada sólo ensucia el índice.
     */
    private PortalDto.AliasLookup audited(PortalDto.AliasLookup result, String criterion,
            PortalUser user, HttpServletRequest request) {
        int found = result.registrations() == null ? 0 : result.registrations().size();
        String ref = found == 1 ? result.registrations().getFirst().aliasUuid() : null;

        audit.record(new AuditEntry(AuditOperation.ALIAS_QUERY, AuditResult.EXITO, "ALIAS", ref,
                200, Map.of("criterio", criterion,
                        "resultado", String.valueOf(result.outcome()),
                        "registros", String.valueOf(found))), user, request);
        return result;
    }
}
