package com.icg.aliasdirectory.mainapi.availability;

/**
 * Los motivos del Anexo §3.5, más el que faltaba.
 *
 * <p>Van en {@code Rsn.Prtry} de la acmt.024. La enumeración del esquema está
 * cerrada a propósito, así que un valor que no esté aquí no valida.
 */
public enum Reason {

    /** Ya hay registro vigente de este mismo banco, para este mismo DPI. */
    ACTIVE_SAME_BANK,

    /**
     * Hay registro vigente de este mismo banco, pero a nombre de OTRO DPI.
     *
     * <p>No está en el §3.5: se agregó el 17/09/2026 por decisión de VDO
     * (hallazgo H-46). Sin él, este caso se devolvía como ACTIVE_SAME_BANK y el
     * banco entendía «ya lo registró este mismo cliente», que es falso y lleva a
     * decirle algo incorrecto a quien está en la ventanilla.
     */
    ACTIVE_SAME_BANK_DIFF_DPI,

    /** El alias está vigente en otra entidad, a nombre de otro DPI. */
    ACTIVE_OTHER_BANK_DIFF_DPI,

    /**
     * La cuenta ya sostiene un alias vigente.
     *
     * <p>No está en el §3.5 por la misma razón que el anterior: la unicidad del
     * Anexo es Prxy.Id + DPI y no menciona la cuenta. VDO confirmó el
     * 07/10/2026 que una cuenta sostiene un solo alias vigente, y hasta
     * entonces el alta aceptaba dos teléfonos distintos contra la misma cuenta.
     *
     * <p>A diferencia de los otros tres, este motivo no sale de
     * {@link AvailabilityRule}: esa regla decide sobre los registros DEL ALIAS y
     * la cuenta no entra en ellos. Lo decide RegistrationService con una
     * consulta propia.
     */
    ACTIVE_SAME_ACCOUNT,

    /** Bloqueo temporal tras una baja: en 24 h se libera. */
    QUARANTINE
}
