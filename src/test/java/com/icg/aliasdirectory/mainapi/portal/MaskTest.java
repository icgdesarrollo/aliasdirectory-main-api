package com.icg.aliasdirectory.mainapi.portal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El enmascarado que exige el Anexo §8.3 para la cuenta, y que la bandeja de
 * aprobación aplica al alias.
 *
 * <p>Lo que se prueba no es el formato sino la garantía: que de lo devuelto no
 * se pueda reconstruir el valor. Por eso los casos límite —cadenas cortas— son
 * los que importan: es ahí donde «dejar los últimos cuatro» dejaría de ocultar
 * nada.
 */
class MaskTest {

    @Test
    @DisplayName("un IBAN de Guatemala deja ver solo los ultimos cuatro caracteres")
    void masksAllButTheLastFour() {
        assertThat(Mask.lastFour("GT82TRAJ01020000001210029690"))
                .isEqualTo("************************9690");
    }

    @Test
    @DisplayName("un telefono se enmascara con la misma regla")
    void masksAPhoneTheSameWay() {
        assertThat(Mask.lastFour("50255551234")).isEqualTo("*******1234");
    }

    @Test
    @DisplayName("el enmascarado conserva la longitud del original")
    void keepsTheLength() {
        String value = "GT82TRAJ01020000001210029690";
        assertThat(Mask.lastFour(value)).hasSameSizeAs(value);
    }

    @Test
    @DisplayName("una cadena de cuatro o menos se enmascara por completo")
    void masksShortValuesEntirely() {
        assertThat(Mask.lastFour("1234")).isEqualTo("****");
        assertThat(Mask.lastFour("7")).isEqualTo("*");
    }

    @Test
    @DisplayName("cinco caracteres ya dejan ver cuatro, nunca mas")
    void revealsAtMostFour() {
        assertThat(Mask.lastFour("12345")).isEqualTo("*2345");
    }

    @Test
    @DisplayName("nulo y vacio no revientan: devuelven vacio")
    void handlesAbsentValues() {
        assertThat(Mask.lastFour(null)).isEmpty();
        assertThat(Mask.lastFour("")).isEmpty();
    }

    @Test
    @DisplayName("AliasMask sigue dando el mismo resultado mientras exista")
    @SuppressWarnings("deprecation")
    void theDeprecatedAliasMaskDelegates() {
        assertThat(AliasMask.of("50255551234")).isEqualTo(Mask.lastFour("50255551234"));
    }
}
