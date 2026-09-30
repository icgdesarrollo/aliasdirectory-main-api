package com.icg.aliasdirectory.mainapi.cancellation;

import com.icg.aliasdirectory.mainapi.availability.Reason;

import java.time.Duration;
import java.time.Instant;

/**
 * Decide si una baja es inmediata o pasa por cuarentena (Anexo §4, F5).
 *
 * <p>La regla: «Si el registro tiene más de 24 h, pasa a período de gracia
 * (BLOQUEADO 24 h) antes de INACTIVO; si tiene menos, la baja es inmediata.»
 *
 * <p>La razón de que exista el período de gracia es que un alias liberado de
 * inmediato puede ser re-registrado por otra persona el mismo día, y las
 * transferencias que ya iban en camino hacia el titular anterior llegarían a la
 * cuenta del nuevo. Las 24 h son el margen para que eso no ocurra. Un registro
 * recién creado no tiene ese riesgo —nadie alcanzó a usarlo— y por eso su baja
 * no espera.
 *
 * <p>Función pura, como las reglas de disponibilidad: recibe el instante de alta
 * y el de ahora, y no toca reloj ni base de datos. Así las dos fronteras de la
 * ventana se pueden probar sin esperar un día.
 */
public final class QuarantineRule {

    /** Las 24 h del Anexo, tanto para medir la antigüedad como para el bloqueo. */
    public static final Duration GRACE = Duration.ofHours(24);

    private QuarantineRule() {
    }

    /**
     * @param status          estado al que pasa el registro
     * @param quarantineUntil fin del bloqueo, o {@code null} si la baja es
     *                        inmediata. La restricción
     *                        {@code ck_registration_quarantine} exige que vaya
     *                        con BLOQUEADO y sólo con él
     * @param reason          QUARANTINE cuando queda bloqueado, o {@code null}
     */
    public record Outcome(String status, Instant quarantineUntil, Reason reason) {

        public boolean quarantined() {
            return reason != null;
        }
    }

    public static Outcome decide(Instant registeredAt, Instant now) {
        // Estrictamente mayor: un registro de exactamente 24 h no «tiene más de
        // 24 h». La frontera importa poco en la práctica y mucho en la prueba,
        // así que se fija aquí en vez de quedar a merced de cómo se escriba la
        // comparación.
        boolean olderThanGrace = Duration.between(registeredAt, now).compareTo(GRACE) > 0;
        return olderThanGrace
                ? new Outcome("BLOQUEADO", now.plus(GRACE), Reason.QUARANTINE)
                : new Outcome("INACTIVO", null, null);
    }
}
