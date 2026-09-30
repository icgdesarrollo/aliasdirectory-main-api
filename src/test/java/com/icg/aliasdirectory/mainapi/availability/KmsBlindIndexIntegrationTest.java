package com.icg.aliasdirectory.mainapi.availability;

import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El índice ciego del KMS tiene que comportarse igual que el local.
 *
 * <p>Las normalizaciones son las que se rompen en silencio: un HMAC sobre un
 * valor mal normalizado devuelve 32 bytes válidos que no coinciden con nada. Si
 * la implementación del KMS normalizara distinto de la local, lo escrito en un
 * ambiente dejaría de encontrarse en el otro, y eso sólo se vería en producción.
 *
 * <p>Son las mismas equivalencias de {@code BlindIndexTest}, comprobadas ahora
 * del otro lado de la red.
 */
@Tag("integration")
class KmsBlindIndexIntegrationTest {

    private final BlindIndex index = new KmsBlindIndex(
            new TransitClient(IntegrationOpenBao.url(), IntegrationOpenBao.TOKEN, 2000),
            IntegrationOpenBao.INDEX_KEY);

    @Test
    @DisplayName("el teléfono se normaliza igual que en la implementación local")
    void telefonoNormalizado() {
        byte[] esperado = index.ofAlias("+50244444444");
        for (String variante : new String[] {
                "+502 4444 4444", "+502-4444-4444", "+502 (4444) 4444", " +50244444444 " }) {
            assertThat(index.ofAlias(variante))
                    .as("«%s» tiene que dar el mismo índice", variante)
                    .isEqualTo(esperado);
        }
    }

    @Test
    @DisplayName("un teléfono sin prefijo se rechaza antes de llegar al KMS")
    void telefonoSinPrefijo() {
        // La validación ocurre en el Normalizer, así que ni siquiera se gasta
        // una llamada de red en un valor que no se debe indexar.
        assertThatThrownBy(() -> index.ofAlias("44444444"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    @DisplayName("el IBAN se normaliza: agrupado o corrido, mayúsculas o minúsculas")
    void ibanNormalizado() {
        byte[] esperado = index.ofIban("GT18BAGU01010000000000111111");
        assertThat(index.ofIban("GT18 BAGU 0101 0000 0000 0011 1111")).isEqualTo(esperado);
        assertThat(index.ofIban("gt18bagu01010000000000111111")).isEqualTo(esperado);
    }

    @Test
    @DisplayName("el DPI se normaliza a dígitos")
    void dpiNormalizado() {
        assertThat(index.ofDpi("1345 98665 4379")).isEqualTo(index.ofDpi("1345986654379"));
    }

    @Test
    @DisplayName("los tres índices miden 32 bytes, que es lo que declara la columna")
    void treintaYDosBytes() {
        assertThat(index.ofAlias("+50244444444")).hasSize(32);
        assertThat(index.ofDpi("1345986654379")).hasSize(32);
        assertThat(index.ofIban("GT18BAGU01010000000000111111")).hasSize(32);
    }

    @Test
    @DisplayName("la versión de llave la reporta el KMS, no la configuración")
    void versionDelKms() {
        assertThat(index.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("la huella del KMS NO coincide con la local: son llaves distintas")
    void llavesDistintasNoCoinciden() {
        // Vale la pena fijarlo: al encender el KMS en un ambiente que venía con
        // la implementación local, TODO el padrón queda sin encontrarse hasta
        // reindexar. No es una migración transparente.
        var local = new LocalHmacBlindIndex(
                java.util.Base64.getEncoder().encodeToString(
                        "llave-de-prueba-de-32-bytes-1234".getBytes(
                                java.nio.charset.StandardCharsets.UTF_8)), 1);
        assertThat(index.ofAlias("+50244444444")).isNotEqualTo(local.ofAlias("+50244444444"));
    }
}
