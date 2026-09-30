package com.icg.aliasdirectory.mainapi.portal;

/**
 * Enmascarado de un alias para las vistas donde el valor completo no hace falta.
 *
 * <p>La bandeja de aprobación es el caso: quien aprueba decide sobre un registro,
 * no sobre una persona, y ver el teléfono entero no cambia su decisión. Lo que sí
 * cambia es cuánta gente termina con el padrón a la vista (regla T-8).
 */
public final class AliasMask {

    private static final int VISIBLE_DIGITS = 4;
    private static final char MASK = '*';

    /**
     * Deja los últimos cuatro caracteres y sustituye el resto.
     *
     * <p>Un alias de cuatro caracteres o menos se enmascara por completo: dejar
     * visible un valor que es todo él los «últimos cuatro» no enmascara nada.
     */
    public static String of(String alias) {
        if (alias == null || alias.isEmpty()) {
            return "";
        }
        if (alias.length() <= VISIBLE_DIGITS) {
            return String.valueOf(MASK).repeat(alias.length());
        }
        int hidden = alias.length() - VISIBLE_DIGITS;
        return String.valueOf(MASK).repeat(hidden) + alias.substring(hidden);
    }

    private AliasMask() {
    }
}
