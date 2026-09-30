package com.icg.aliasdirectory.mainapi.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La normalización, que es la parte del índice ciego que se rompe en silencio.
 *
 * <p>Un HMAC mal calculado no falla: devuelve 32 bytes que no coinciden con
 * nada. El alias queda registrado dos veces en una tabla cuyo índice único decía
 * impedirlo, o una consulta contesta «disponible» sobre un teléfono que sí está
 * en el padrón. Por eso las equivalencias se fijan aquí una por una.
 */
class BlindIndexTest {

    private static LocalHmacBlindIndex withKey(String semilla) {
        return new LocalHmacBlindIndex(Base64.getEncoder().encodeToString(
                semilla.getBytes(StandardCharsets.UTF_8)), 1);
    }

    private final LocalHmacBlindIndex index = withKey("llave-de-prueba-de-32-bytes-1234");

    @Test
    @DisplayName("el teléfono se normaliza: espacios, guiones y paréntesis dan el mismo índice")
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
    @DisplayName("un teléfono sin prefijo se rechaza, no se le supone +502")
    void telefonoSinPrefijo() {
        // Suponer el país produciría un índice para un alias que el banco no
        // pidió, y la respuesta hablaría de otro teléfono.
        assertThatThrownBy(() -> index.ofAlias("44444444"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("E.164");
    }

    @Test
    @DisplayName("el DPI se normaliza a dígitos")
    void dpiNormalizado() {
        assertThat(index.ofDpi("1345 98665 4379")).isEqualTo(index.ofDpi("1345986654379"));
        assertThatThrownBy(() -> index.ofDpi("sin-digitos"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("el IBAN se normaliza: agrupado o corrido, mayúsculas o minúsculas")
    void ibanNormalizado() {
        byte[] esperado = index.ofIban("GT18BAGU01010000000000111111");
        assertThat(index.ofIban("GT18 BAGU 0101 0000 0000 0011 1111")).isEqualTo(esperado);
        assertThat(index.ofIban("gt18bagu01010000000000111111")).isEqualTo(esperado);
        assertThatThrownBy(() -> index.ofIban("no-es-un-iban"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("dos llaves distintas dan índices distintos para el mismo valor")
    void laLlaveSepara() {
        // Es lo que hace que rotar la llave obligue a reindexar: el índice viejo
        // deja de encontrarse, no se «actualiza» solo.
        assertThat(withKey("llave-de-prueba-de-32-bytes-1234").ofAlias("+50244444444"))
                .isNotEqualTo(withKey("OTRA-llave-de-32-bytes-abcdefghi").ofAlias("+50244444444"));
    }

    @Test
    @DisplayName("el índice mide 32 bytes, que es lo que declara la columna")
    void treintaYDosBytes() {
        assertThat(index.ofAlias("+50244444444")).hasSize(32);
        assertThat(index.ofDpi("1345986654379")).hasSize(32);
        assertThat(index.ofIban("GT18BAGU01010000000000111111")).hasSize(32);
    }

    @Test
    @DisplayName("una llave corta se rechaza al construir, no al primer uso")
    void llaveCorta() {
        assertThatThrownBy(() -> withKey("corta"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }
}
