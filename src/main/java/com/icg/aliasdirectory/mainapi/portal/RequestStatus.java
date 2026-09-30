package com.icg.aliasdirectory.mainapi.portal;

/**
 * El estado de una solicitud de baja, traducido del esquema al contrato JSON.
 *
 * <p>La tabla {@code portal_deactivation_request} guarda sus estados en español
 * —PENDIENTE, APROBADA, RECHAZADA, EJECUTADA, EXPIRADA— porque la migración
 * V1.0.2 los declaró así. El contrato de esta API está en inglés, como el resto
 * del código, y pasar el valor de la columna tal cual ata el portal al nombre de
 * un enum de MySQL: si mañana el esquema renombra un estado, el portal deja de
 * reconocerlo sin que nada falle a la vista.
 *
 * <p>Un valor que el esquema tenga y esta traducción no, sale como
 * {@code UNKNOWN} en vez de romper: el portal lo mostrará como desconocido y no
 * ofrecerá acciones sobre él, que es el lado seguro.
 */
public enum RequestStatus {

    PENDING,
    APPROVED,
    REJECTED,
    EXECUTED,
    EXPIRED,
    UNKNOWN;

    public static String of(String schemaValue) {
        if (schemaValue == null) {
            return UNKNOWN.name();
        }
        return switch (schemaValue) {
            case "PENDIENTE" -> PENDING.name();
            case "APROBADA" -> APPROVED.name();
            case "RECHAZADA" -> REJECTED.name();
            case "EJECUTADA" -> EXECUTED.name();
            case "EXPIRADA" -> EXPIRED.name();
            default -> UNKNOWN.name();
        };
    }
}
