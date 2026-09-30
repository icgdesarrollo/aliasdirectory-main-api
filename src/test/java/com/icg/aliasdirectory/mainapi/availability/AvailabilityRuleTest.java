package com.icg.aliasdirectory.mainapi.availability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La tabla de estados del Anexo, fila por fila.
 *
 * <p>Incluye las dos filas que el §3.5 no cubría y que se resolvieron el
 * 17/09/2026 (hallazgo H-46): alias activo en el propio banco con otro DPI, que
 * ahora tiene código, y alias activo en otra entidad con el MISMO DPI, que no es
 * conflicto y no lleva motivo.
 */
class AvailabilityRuleTest {

    private static final String YO = "GTCOGTGC";
    private static final String OTRO = "INDLGTGC";

    private static ActiveRegistration active(String bic, boolean sameDpi) {
        return new ActiveRegistration(bic, true, sameDpi);
    }

    private static ActiveRegistration bloqueado(String bic, boolean sameDpi) {
        return new ActiveRegistration(bic, false, sameDpi);
    }

    @Test
    @DisplayName("sin registros vigentes: disponible, sin motivo")
    void disponible() {
        var r = AvailabilityRule.decide(List.of(), YO);
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).isEmpty();
    }

    @Test
    @DisplayName("activo en mi banco con el mismo DPI: ACTIVE_SAME_BANK")
    void mismoBancoMismoDpi() {
        var r = AvailabilityRule.decide(List.of(active(YO, true)), YO);
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).contains(Reason.ACTIVE_SAME_BANK);
        assertThat(r.reasonBic()).isEmpty();
    }

    @Test
    @DisplayName("activo en mi banco con OTRO DPI: ACTIVE_SAME_BANK_DIFF_DPI (H-46)")
    void mismoBancoOtroDpi() {
        var r = AvailabilityRule.decide(List.of(active(YO, false)), YO);
        assertThat(r.reason()).contains(Reason.ACTIVE_SAME_BANK_DIFF_DPI);
        // No se devuelve el BIC: el banco ya sabe que es él.
        assertThat(r.reasonBic()).isEmpty();
    }

    @Test
    @DisplayName("activo en otra entidad con otro DPI: conflicto, y se dice en cuál")
    void otroBancoOtroDpi() {
        var r = AvailabilityRule.decide(List.of(active(OTRO, false)), YO);
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).contains(Reason.ACTIVE_OTHER_BANK_DIFF_DPI);
        assertThat(r.reasonBic()).contains(OTRO);
    }

    @Test
    @DisplayName("activo en otra entidad con el MISMO DPI: multibanco, sin motivo")
    void otroBancoMismoDpi() {
        var r = AvailabilityRule.decide(List.of(active(OTRO, true)), YO);
        // El alias existe, y aun así este banco puede registrarlo: es la misma
        // persona. Devolver ACTIVE_OTHER_BANK_DIFF_DPI aquí sería el error que
        // invita a cometer el nombre del código.
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).isEmpty();
    }

    @Test
    @DisplayName("en cuarentena: QUARANTINE, y Vrfctn=false porque no hay registro activo")
    void cuarentena() {
        var r = AvailabilityRule.decide(List.of(bloqueado(YO, true)), YO);
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).contains(Reason.QUARANTINE);
    }

    @Test
    @DisplayName("conflicto por DPI y cuarentena a la vez: manda el conflicto")
    void elConflictoGanaALaCuarentena() {
        // El orden no es arbitrario: la cuarentena caduca en 24 h y el conflicto
        // por DPI no. Devolver QUARANTINE haría que el banco esperara un día para
        // descubrir después que el alias es de otra persona.
        var r = AvailabilityRule.decide(
                List.of(bloqueado(YO, true), active(OTRO, false)), YO);
        assertThat(r.reason()).contains(Reason.ACTIVE_OTHER_BANK_DIFF_DPI);
    }

    @Test
    @DisplayName("el conflicto en el propio banco se prefiere al de otra entidad")
    void ownBankWinsOverOther() {
        var r = AvailabilityRule.decide(
                List.of(active(OTRO, false), active(YO, false)), YO);
        assertThat(r.reason()).contains(Reason.ACTIVE_SAME_BANK_DIFF_DPI);
    }

    @Test
    @DisplayName("cada Reason está en la enumeración del XSD, y al revés")
    void elEnumYElEsquemaNoSeSeparan() throws Exception {
        // Si alguien agrega un valor a Reason y no al XSD, la respuesta deja de
        // validar recién en producción, contra un banco. Esto lo detecta en el
        // build, leyendo el esquema de verdad y no una lista copiada aquí.
        String xsd;
        try (var in = getClass().getClassLoader()
                .getResourceAsStream("esquemas/perfiles/acmt024-disponibilidad-registro.xsd")) {
            assertThat(in).as("el perfil de respuesta tiene que estar en el classpath").isNotNull();
            xsd = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }

        var enElXsd = java.util.regex.Pattern
                .compile("<xs:enumeration value=\"(ACTIVE_[A-Z_]+|QUARANTINE)\"/>")
                .matcher(xsd).results()
                .map(r -> r.group(1))
                .collect(java.util.stream.Collectors.toSet());

        var enElEnum = java.util.Arrays.stream(Reason.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(enElEnum).isEqualTo(enElXsd);
    }
}
