package com.icg.aliasdirectory.mainapi.availability;

/**
 * Índice ciego: el HMAC con el que se busca sin descifrar.
 *
 * <p>El padrón guarda alias, DPI e IBAN cifrados, y a la par un HMAC-SHA256 del
 * valor normalizado. La búsqueda va contra el HMAC, no contra el texto: por eso
 * una consulta no necesita descifrar una sola fila. Sin esto habría que traer el
 * padrón entero y descifrarlo para comparar, que es lo que el Anexo §5.1 prohíbe
 * y lo que haría imposible el SLA de 100 ms.
 *
 * <p><b>Por qué esto es una interfaz y no una clase.</b> La seguridad del índice
 * ciego descansa por completo en que su llave no se filtre, y el espacio de un
 * alias telefónico guatemalteco es diminuto: ocho dígitos con cuatro prefijos
 * móviles son 4·10⁷ valores. Medido: enumerarlos todos y calcular su HMAC toma
 * <b>1.8 minutos</b> en un solo hilo de Python, y del orden de <b>0.04 segundos</b>
 * con hashcat en una GPU. Quien obtenga la llave reconstruye la columna entera y
 * sabe qué teléfonos están en el padrón; el DPI aguanta algo más —13 dígitos,
 * unos 17 minutos en GPU— pero tampoco es una defensa.
 *
 * <p>De ahí que <b>la llave del índice no puede vivir donde la aplicación la
 * vea</b>: tiene que calcularse dentro del KMS, igual que el cifrado, y por eso
 * el contrato es «dame el índice» y no «dame la llave». Hoy la única
 * implementación es {@link LocalHmacBlindIndex}, que sí tiene la llave en
 * memoria y sirve para desarrollo y pruebas; la del KMS es E03-D03.
 *
 * <p>Las normalizaciones son parte del contrato: dos escrituras del mismo valor
 * que normalicen distinto producen índices distintos y el registro queda
 * duplicado en una tabla cuyo índice único decía impedirlo.
 */
public interface BlindIndex {

    /** Alias telefónico normalizado a E.164. */
    byte[] ofAlias(String alias);

    /** DPI, sólo dígitos. */
    byte[] ofDpi(String dpi);

    /** IBAN en mayúsculas y sin separadores (ISO 13616). */
    byte[] ofIban(String iban);

    /**
     * Fingerprint del IdCliente del banco (SchmeNm.Prtry = CUSTOMER_ID).
     *
     * <p>Tiene método propio y no reusa {@link #ofDpi} aunque ambos sean cadenas:
     * el IdCliente es un identificador interno de cada banco, con el formato que
     * cada uno use, y normalizarlo como si fuera un DPI le quitaría caracteres
     * que para ese banco son significativos. Dos clientes distintos podrían
     * terminar con la misma huella, y {@code uk_customer_bank} rechazaría el alta
     * del segundo sin explicación posible.
     */
    byte[] ofCustomerId(String customerId);

    /**
     * Versión de la llave con la que se calcularon estos índices.
     *
     * <p>Va a las columnas {@code *_bidx_ver}. No se usa para filtrar en las
     * búsquedas: un índice calculado con otra llave simplemente no coincide, así
     * que agregar la versión al WHERE no cambiaría el resultado y sí impediría
     * encontrar filas durante una rotación. Sirve para saber qué filas faltan
     * reindexar, que es la única forma de rotar esta llave: recalcular el índice
     * de cada fila con la llave nueva, porque el valor original sólo se puede
     * recuperar descifrando.
     */
    int version();
}
