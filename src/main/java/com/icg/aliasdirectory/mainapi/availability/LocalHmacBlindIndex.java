package com.icg.aliasdirectory.mainapi.availability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Índice ciego calculado en el proceso, con la llave en configuración.
 *
 * <p><b>No apto para producción</b>, y no por prudencia genérica: la llave queda
 * en la configuración de la aplicación y en las variables de entorno del pod,
 * donde la ve cualquiera con acceso de lectura a un despliegue. Y según la
 * medición anotada en {@link BlindIndex}, quien tenga esa llave reconstruye en
 * segundos el índice de todos los teléfonos de Guatemala.
 *
 * <p>Existe para desarrollo y para las pruebas que no levantan el KMS, donde el
 * padrón es sintético. La implementación de producción es {@link KmsBlindIndex},
 * que calcula el HMAC dentro de OpenBao; se elige con
 * {@code icg.kms.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "false", matchIfMissing = true)
public class LocalHmacBlindIndex implements BlindIndex {

    private static final String ALGORITHM = "HmacSHA256";

    private final byte[] key;
    private final int version;

    public LocalHmacBlindIndex(@Value("${icg.blind-index.key}") String keyBase64,
            @Value("${icg.blind-index.version:1}") int version) {
        this.key = Base64.getDecoder().decode(keyBase64);
        this.version = version;
        if (key.length < 32) {
            throw new IllegalStateException(
                    "La llave del índice ciego tiene " + key.length + " bytes; "
                    + "HMAC-SHA256 necesita 32 o más");
        }
    }

    @Override
    public int version() {
        return version;
    }

    @Override
    public byte[] ofAlias(String alias) {
        return hmac(Normalizer.phone(alias));
    }

    @Override
    public byte[] ofDpi(String dpi) {
        return hmac(Normalizer.dpi(dpi));
    }

    @Override
    public byte[] ofIban(String iban) {
        return hmac(Normalizer.iban(iban));
    }

    @Override
    public byte[] ofCustomerId(String customerId) {
        return hmac(Normalizer.customerId(customerId));
    }

    private byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("No se pudo calcular el índice ciego", e);
        }
    }
}
