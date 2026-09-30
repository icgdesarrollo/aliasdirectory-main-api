package com.icg.aliasdirectory.mainapi.portal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La traducción del estado entre el esquema y el contrato JSON.
 *
 * <p>Existe porque ya falló una vez: el BFF devolvía PENDIENTE y el portal
 * comparaba contra PENDING, así que los botones de aprobar no aparecían y nada
 * en el sistema dio error. Una prueba aquí convierte ese silencio en un fallo
 * de compilación.
 */
class RequestStatusTest {

    @Test
    @DisplayName("cada estado del esquema tiene su equivalente en el contrato")
    void translatesEverySchemaState() {
        assertThat(RequestStatus.of("PENDIENTE")).isEqualTo("PENDING");
        assertThat(RequestStatus.of("APROBADA")).isEqualTo("APPROVED");
        assertThat(RequestStatus.of("RECHAZADA")).isEqualTo("REJECTED");
        assertThat(RequestStatus.of("EJECUTADA")).isEqualTo("EXECUTED");
        assertThat(RequestStatus.of("EXPIRADA")).isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("un estado que el esquema agregue sale como UNKNOWN, no revienta")
    void failsSafeOnAnUnknownState() {
        assertThat(RequestStatus.of("ANULADA")).isEqualTo("UNKNOWN");
        assertThat(RequestStatus.of("")).isEqualTo("UNKNOWN");
        assertThat(RequestStatus.of(null)).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("la traduccion no distingue por mayusculas accidentales")
    void doesNotGuessAtNearMisses() {
        // 'pendiente' en minusculas no es un valor del enum del esquema: si
        // apareciera, algo cambio y el portal no debe ofrecer acciones sobre el.
        assertThat(RequestStatus.of("pendiente")).isEqualTo("UNKNOWN");
    }
}
