package com.icg.aliasdirectory.mainapi.cancellation;

import static org.assertj.core.api.Assertions.assertThat;

import com.icg.aliasdirectory.mainapi.availability.Reason;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La ventana de 24 h de la baja, por sus dos lados.
 *
 * <p>Se prueba sin esperar un día porque la regla recibe los dos instantes y no
 * lee el reloj. Esa es la razón de que sea una función pura y no un método del
 * servicio.
 */
class QuarantineRuleTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    @Test
    @DisplayName("registro recien creado: baja inmediata, sin cuarentena")
    void freshRegistrationIsCancelledOutright() {
        var outcome = QuarantineRule.decide(NOW.minus(Duration.ofHours(1)), NOW);

        assertThat(outcome.status()).isEqualTo("INACTIVO");
        assertThat(outcome.quarantineUntil()).isNull();
        assertThat(outcome.reason()).isNull();
        assertThat(outcome.quarantined()).isFalse();
    }

    @Test
    @DisplayName("registro de mas de 24 h: cuarentena de 24 h antes de INACTIVO")
    void oldRegistrationGoesToQuarantine() {
        var outcome = QuarantineRule.decide(NOW.minus(Duration.ofHours(25)), NOW);

        assertThat(outcome.status()).isEqualTo("BLOQUEADO");
        assertThat(outcome.reason()).isEqualTo(Reason.QUARANTINE);
        assertThat(outcome.quarantined()).isTrue();
        assertThat(outcome.quarantineUntil()).isEqualTo(NOW.plus(QuarantineRule.GRACE));
    }

    /**
     * La frontera. El Anexo dice «más de 24 h», así que exactamente 24 h no
     * entra en cuarentena. Importa poco en producción y mucho aquí: sin esta
     * prueba, cambiar el {@code >} por un {@code >=} pasaría inadvertido.
     */
    @Test
    @DisplayName("exactamente 24 h no es 'mas de 24 h': baja inmediata")
    void exactlyTwentyFourHoursIsNotOlder() {
        var outcome = QuarantineRule.decide(NOW.minus(QuarantineRule.GRACE), NOW);

        assertThat(outcome.status()).isEqualTo("INACTIVO");
        assertThat(outcome.quarantined()).isFalse();
    }

    @Test
    @DisplayName("un segundo mas alla de las 24 h ya es cuarentena")
    void oneSecondPastTheWindowIsQuarantine() {
        var outcome = QuarantineRule.decide(
                NOW.minus(QuarantineRule.GRACE).minusSeconds(1), NOW);

        assertThat(outcome.status()).isEqualTo("BLOQUEADO");
        assertThat(outcome.quarantined()).isTrue();
    }

    /**
     * La restricción {@code ck_registration_quarantine} de la base exige que
     * {@code quarantine_until} vaya con BLOQUEADO y sólo con él. Si la regla lo
     * devolviera al revés, la baja fallaría contra el motor y no aquí.
     */
    @Test
    @DisplayName("la fecha de liberacion acompania a BLOQUEADO y solo a el")
    void quarantineDateOnlyWithBlockedStatus() {
        for (long hours : new long[] {0, 1, 23, 24, 25, 48, 24 * 30}) {
            var outcome = QuarantineRule.decide(NOW.minus(Duration.ofHours(hours)), NOW);
            assertThat(outcome.quarantineUntil() != null)
                    .as("antiguedad de %d h", hours)
                    .isEqualTo("BLOQUEADO".equals(outcome.status()));
        }
    }
}
