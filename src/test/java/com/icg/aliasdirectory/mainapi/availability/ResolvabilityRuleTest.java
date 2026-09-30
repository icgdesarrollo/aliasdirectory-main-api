package com.icg.aliasdirectory.mainapi.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Los estados que puede ver Disponibilidad Resolución, que son menos y distintos. */
class ResolvabilityRuleTest {

    private static final String UNO = "INDLGTGC";
    private static final String OTRO = "BAGUGTGC";

    private static AliasRegistration active(String bic) {
        return new AliasRegistration(bic, true);
    }

    private static AliasRegistration bloqueado(String bic) {
        return new AliasRegistration(bic, false);
    }

    @Test
    @DisplayName("alias inexistente: no resoluble, sin motivo")
    void inexistente() {
        var r = ResolvabilityRule.decide(List.of());
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).isEmpty();
        assertThat(r.ownerBic()).isEmpty();
    }

    @Test
    @DisplayName("activo en una sola entidad: resoluble, y se dice en cuál")
    void resolvableAtOneBank() {
        var r = ResolvabilityRule.decide(List.of(active(UNO)));
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).isEmpty();
        assertThat(r.ownerBic()).contains(UNO);
    }

    @Test
    @DisplayName("activo en varias entidades: resoluble, pero SIN nombrar una")
    void multibancoNoNombraBanco() {
        // Elegir una de dos sería arbitrario, y el banco podría tomarla como
        // destino de la transferencia. Quién resuelve a qué cuenta lo contesta F3.
        var r = ResolvabilityRule.decide(List.of(active(UNO), active(OTRO)));
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.ownerBic()).isEmpty();
    }

    @Test
    @DisplayName("sólo bloqueado: no resoluble, QUARANTINE")
    void enCuarentena() {
        var r = ResolvabilityRule.decide(List.of(bloqueado(UNO)));
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).contains(Reason.QUARANTINE);
    }

    @Test
    @DisplayName("bloqueado en una entidad y activo en otra: resoluble por la activa")
    void bloqueadoEnUnaActivoEnOtra() {
        // La cuarentena es del registro, no del alias: si otra entidad lo tiene
        // activo, la transferencia se puede resolver ahí.
        var r = ResolvabilityRule.decide(List.of(bloqueado(UNO), active(OTRO)));
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).isEmpty();
        assertThat(r.ownerBic()).contains(OTRO);
    }

    @Test
    @DisplayName("esta regla no puede devolver ningún motivo que compare DPI")
    void soloQuarantine() {
        // Es la invariante del método: sin DPI de entrada, los otros motivos son
        // incalculables. El perfil XSD también lo impide; esto lo fija en el
        // código, para que no dependa de que el esquema atrape el error.
        var casos = List.of(
                List.<AliasRegistration>of(),
                List.of(active(UNO)),
                List.of(bloqueado(UNO)),
                List.of(active(UNO), active(OTRO)),
                List.of(bloqueado(UNO), bloqueado(OTRO)));
        for (var caso : casos) {
            var reason = ResolvabilityRule.decide(caso).reason();
            assertThat(reason).isIn(java.util.Optional.empty(),
                    java.util.Optional.of(Reason.QUARANTINE));
        }
    }
}
