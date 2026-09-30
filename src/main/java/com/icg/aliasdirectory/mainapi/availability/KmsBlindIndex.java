package com.icg.aliasdirectory.mainapi.availability;

import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Índice ciego calculado dentro del KMS. Es la implementación de producción.
 *
 * <p>La diferencia con {@link LocalHmacBlindIndex} no es de forma: allá la
 * llave está en la configuración del proceso, aquí nunca sale de OpenBao. Y esa
 * diferencia es todo el punto, porque el espacio de un alias telefónico
 * guatemalteco se enumera en 1.8 minutos de CPU —0.04 s en GPU—, así que quien
 * obtenga la llave reconstruye la columna entera.
 *
 * <p>Las normalizaciones son las MISMAS, por {@link Normalizer}: el índice se
 * calcula sobre el valor normalizado, y si las dos implementaciones normalizaran
 * distinto, lo escrito en un ambiente no se encontraría en el otro.
 */
@Component
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
public class KmsBlindIndex implements BlindIndex {

    private final TransitClient kms;
    private final String key;

    public KmsBlindIndex(TransitClient kms,
            @Value("${icg.kms.index-key:dir-alias-bidx}") String key) {
        this.kms = kms;
        this.key = key;
    }

    /**
     * La versión no se fija en configuración: la devuelve el KMS en cada huella,
     * dentro del prefijo {@code vault:vN:}. Preguntarla aparte abriría la puerta
     * a que la columna {@code *_bidx_ver} diga una versión y el índice esté
     * calculado con otra, que es justo el dato que sirve para saber qué filas
     * faltan reindexar.
     */
    @Override
    public int version() {
        return kms.hmac(key, "sonda-de-version").version();
    }

    @Override
    public byte[] ofAlias(String alias) {
        return kms.hmac(key, Normalizer.phone(alias)).bytes();
    }

    @Override
    public byte[] ofDpi(String dpi) {
        return kms.hmac(key, Normalizer.dpi(dpi)).bytes();
    }

    @Override
    public byte[] ofIban(String iban) {
        return kms.hmac(key, Normalizer.iban(iban)).bytes();
    }

    @Override
    public byte[] ofCustomerId(String customerId) {
        return kms.hmac(key, Normalizer.customerId(customerId)).bytes();
    }
}
