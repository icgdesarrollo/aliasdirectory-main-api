package com.icg.aliasdirectory.mainapi.portal;

/**
 * Enmascarado de los valores que el portal muestra.
 *
 * <p>El Anexo §8.3 exige que la cuenta se despliegue enmascarada, y la bandeja
 * de aprobación muestra el alias por la misma razón: quien atiende una llamada
 * confirma datos con el cliente, no necesita el valor entero para hacerlo, y
 * cada pantalla que lo muestra es una pantalla desde la que se puede copiar.
 *
 * <p>Enmascara el servidor, no el navegador. Un valor completo que sale del BFF
 * ya está en la máquina del agente aunque la pantalla pinte asteriscos: queda en
 * la respuesta HTTP, en las herramientas de desarrollo y en cualquier extensión
 * que lea la página.
 */
public final class Mask {

    private static final int VISIBLE = 4;
    private static final char MASK = '*';

    /**
     * Deja los últimos cuatro caracteres y sustituye el resto.
     *
     * <p>Un valor de cuatro caracteres o menos se enmascara por completo: dejar
     * visible algo que es todo él «los últimos cuatro» no enmascara nada.
     */
    public static String lastFour(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() <= VISIBLE) {
            return String.valueOf(MASK).repeat(value.length());
        }
        int hidden = value.length() - VISIBLE;
        return String.valueOf(MASK).repeat(hidden) + value.substring(hidden);
    }

    private Mask() {
    }
}
