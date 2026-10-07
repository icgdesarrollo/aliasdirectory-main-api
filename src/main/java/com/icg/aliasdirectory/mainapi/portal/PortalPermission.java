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
    public static final String QUERY_BY_ACCOUNT = "ALIAS_CONSULTAR_POR_CUENTA";
    public static final String QUERY_BY_DPI = "ALIAS_CONSULTAR_POR_DPI";
    public static final String QUERY_BY_CUSTOMER = "ALIAS_CONSULTAR_POR_CLIENTE";
    public static final String CANCEL_REQUEST = "ALIAS_BAJA_SOLICITAR";
    public static final String CANCEL_APPROVE = "ALIAS_BAJA_APROBAR";
    public static final String CANCEL_ALL_BANKS = "ALIAS_BAJA_ALL_BANKS";

    /** Administración delegada de agentes de la propia entidad (E15-D04). */
    public static final String USER_ADMIN = "USUARIO_ADMINISTRAR";
    public static final String ROLE_ASSIGN = "ROL_ASIGNAR";

    /** Bitácora del portal (E15-D05, Anexo §8.4). */
    public static final String AUDIT_READ = "BITACORA_CONSULTAR";
    public static final String AUDIT_READ_GLOBAL = "BITACORA_CONSULTAR_GLOBAL";

    public static final String BANK_ADMIN = "BANCO_ADMINISTRAR";

    private PortalPermission() {
    }
}
