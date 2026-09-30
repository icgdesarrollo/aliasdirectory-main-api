package com.icg.aliasdirectory.mainapi.portal;

/**
 * Los permisos del portal, tal como los puebla {@code R__reference_catalogs.sql}.
 *
 * <p>Son los que manda la autorización, y NO los roles del token. La migración
 * V1.0.2 lo decidió así (Anexo §8.5): Keycloak dice quién es el usuario, el
 * padrón dice qué puede hacer y a nombre de qué entidad. Un token con un rol de
 * más no habilita nada, porque el BFF nunca lo mira.
 */
public final class PortalPermission {

    public static final String QUERY_BY_ALIAS = "ALIAS_CONSULTAR_POR_ALIAS";
    public static final String CANCEL_REQUEST = "ALIAS_BAJA_SOLICITAR";
    public static final String CANCEL_APPROVE = "ALIAS_BAJA_APROBAR";
    public static final String CANCEL_ALL_BANKS = "ALIAS_BAJA_ALL_BANKS";

    private PortalPermission() {
    }
}
