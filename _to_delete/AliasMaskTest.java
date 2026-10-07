package com.icg.aliasdirectory.mainapi.portal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El enmascarado que ve quien aprueba una baja.
 *
 * <p>Lo que se prueba no es el formato sino la garantía: que de lo devuelto no
 * se pueda reconstruir el alias. Por eso los casos límite —cadenas cortas— son
 * los que importan: es ahí donde «dejar los últimos cuatro» dejaría de ocultar
 * nada.
 */
class AliasMaskTest {

    @Test
    @DisplayName("un telefono de Guatemala deja ver solo los ultimos cuatro digitos")
    void masksAllButTheLastFour() {
        assertThat(AliasMask.of("50255551234")).isEqualTo("*******1234");
    }

    @Test
    @DisplayName("el enmascarado conserva la longitud del original")
    void keepsTheLength() {
        String alias = "50255551234";
        assertThat(AliasMask.of(alias)).hasSameSizeAs(alias);
    }

    @Test
    @DisplayName("una cadena de cuatro o menos se enmascara por completo")
    void masksShortValuesEntirely() {
        assertThat(AliasMask.of("1234")).isEqualTo("****");
        assertThat(AliasMask.of("7")).isEqualTo("*");
    }

    @Test
    @DisplayName("cinco caracteres ya dejan ver cuatro, nunca mas")
    void revealsAtMostFour() {
        assertThat(AliasMask.of("12345")).isEqualTo("*2345");
    }

    @Test
    @DisplayName("nulo y vacio no revientan: devuelven vacio")
    void handlesAbsentValues() {
        assertThat(AliasMask.of(null)).isEmpty();
        assertThat(AliasMask.of("")).isEmpty();
    }
}
