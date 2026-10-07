package com.icg.aliasdirectory.mainapi.portal;

/**
 * Enmascarado de un alias.
 *
 * @deprecated Usar {@link Mask#lastFour(String)}. La regla es la misma para el
 *             alias y para la cuenta, y tenerla escrita dos veces garantiza que
 *             un día cambie en un lado y no en el otro.
 */
@Deprecated(forRemoval = true)
public final class AliasMask {

    public static String of(String alias) {
        return Mask.lastFour(alias);
    }

    private AliasMask() {
    }
}
