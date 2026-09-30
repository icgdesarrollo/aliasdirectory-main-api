package com.icg.aliasdirectory.mainapi.availability;

import java.util.Locale;

/**
 * Las normalizaciones que preceden a todo índice ciego.
 *
 * <p>Vive aparte de las implementaciones de {@link BlindIndex} a propósito: el
 * índice se calcula sobre el valor NORMALIZADO, así que la implementación local
 * y la del KMS tienen que normalizar idéntico o producirán huellas distintas
 * para el mismo dato. Duplicar estas tres funciones sería la forma más fácil de
 * que un ambiente deje de encontrar lo que otro escribió.
 *
 * <p>Nada de esto falla ruidosamente si se hace mal: un HMAC sobre un valor mal
 * normalizado devuelve 32 bytes perfectamente válidos que no coinciden con
 * nada. De ahí que las equivalencias estén fijadas una por una en las pruebas.
 *
 * <p>Es pública porque el registro (F2) la necesita desde su propio paquete, y
 * tiene que ser LA MISMA que usa la consulta: si el alta normaliza distinto que
 * la búsqueda, el alias se guarda con una huella y se busca con otra, y el
 * padrón responde «no existe» sobre un registro que está ahí.
 */
public final class Normalizer {

    private Normalizer() {
    }

    /**
     * E.164: sin espacios ni separadores, con prefijo de país.
     *
     * <p>Se rechaza un número sin prefijo en vez de suponer +502. Suponerlo
     * produciría un índice para un alias que el banco no pidió, y en una
     * consulta de disponibilidad eso significa contestar sobre el teléfono
     * equivocado.
     */
    public static String phone(String alias) {
        String limpio = alias.replaceAll("[\\s()\\-.]", "").toUpperCase(Locale.ROOT);
        if (!limpio.startsWith("+")) {
            throw new IllegalArgumentException("El alias debe venir en E.164, con prefijo +");
        }
        if (!limpio.substring(1).matches("[0-9]{6,15}")) {
            throw new IllegalArgumentException("El alias no parece un teléfono E.164");
        }
        return limpio;
    }

    /** DPI: sólo dígitos. */
    public static String dpi(String dpi) {
        String limpio = dpi.replaceAll("[^0-9]", "");
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException("DPI sin dígitos");
        }
        return limpio;
    }

    /**
     * IBAN en mayúsculas, sin espacios ni guiones (ISO 13616).
     *
     * <p>Los bancos lo escriben agrupado de cuatro en cuatro para leerlo; el
     * formato electrónico no lleva separadores. Normalizar es lo que hace que
     * {@code GT18 BAGU 0101 …} y {@code gt18bagu0101…} sean la misma cuenta.
     */
    public static String iban(String iban) {
        String limpio = iban.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
        if (!limpio.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{1,30}")) {
            throw new IllegalArgumentException("El IBAN no tiene la forma de ISO 13616");
        }
        return limpio;
    }

    /**
     * IdCliente: se recortan los espacios de los extremos y nada mas.
     *
     * <p>Deliberadamente minimo. El formato del IdCliente lo define cada banco y
     * el directorio no lo conoce: puede llevar guiones, ceros a la izquierda o
     * distinguir mayusculas, y cualquiera de esas cosas puede ser significativa.
     * Quitar caracteres «de mas» aqui haria colisionar clientes distintos del
     * mismo banco, que es justo lo que uk_customer_bank rechazaria.
     *
     * <p>Lo unico que se quita son los espacios de los bordes, que no los pone
     * el banco a proposito sino el XML.
     */
    public static String customerId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("El IdCliente es obligatorio");
        }
        String limpio = value.strip();
        if (limpio.isEmpty()) {
            throw new IllegalArgumentException("El IdCliente no puede ser vacio");
        }
        return limpio;
    }
}
