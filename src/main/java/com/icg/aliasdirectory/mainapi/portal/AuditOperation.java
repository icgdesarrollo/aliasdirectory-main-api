package com.icg.aliasdirectory.mainapi.portal;

/**
 * Los tipos de operación que registra la bitácora.
 *
 * <p>Constantes y no un enum de base de datos: {@code portal_audit_log.operation}
 * es VARCHAR a propósito, porque la bitácora es inmutable y tiene que poder
 * seguir leyéndose aunque mañana se retire una operación del código. Un enum de
 * Java que se quede corto al leer una fila antigua rompería la consulta de
 * auditoría, que es justo lo que no puede fallar.
 */
public final class AuditOperation {

    public static final String ALIAS_QUERY = "ALIAS_CONSULTA";
    public static final String CANCEL_REQUEST = "BAJA_SOLICITUD";
    public static final String CANCEL_APPROVE = "BAJA_APROBACION";
    public static final String CANCEL_REJECT = "BAJA_RECHAZO";
    public static final String USER_CREATE = "USUARIO_ALTA";
    public static final String USER_UPDATE = "USUARIO_MODIFICACION";
    public static final String USER_STATUS = "USUARIO_CAMBIO_ESTADO";
    public static final String ROLE_ASSIGN = "ROL_ASIGNACION";
    public static final String ROLE_REVOKE = "ROL_REVOCACION";
    public static final String AUDIT_QUERY = "BITACORA_CONSULTA";

    private AuditOperation() {
    }
}
