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

    /** Bloqueo temporal tras una baja: en 24 h se libera. */
    QUARANTINE
}
