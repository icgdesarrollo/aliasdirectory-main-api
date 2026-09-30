package com.icg.aliasdirectory.mainapi.availability;

import java.util.List;
import java.util.Optional;

/**
 * Decide, a partir de los registros vigentes de un alias, qué contestar.
 *
 * <p>Es una función pura: no toca base de datos ni XML. Así la tabla de estados
 * del Anexo se puede probar entera sin levantar nada, que es lo que hace que las
 * pruebas de esta regla valgan algo.
 *
 * <p><b>Qué significa cada campo de la respuesta.</b> Los dos no dicen lo mismo,
 * y confundirlos es fácil:
 * <ul>
 *   <li>{@code Vrfctn} responde «¿existe un registro ACTIVO de este alias?».
 *   <li>{@code Rsn} aparece sólo si hay un impedimento para que ESTE banco lo
 *       registre para ESTE DPI.
 * </ul>
 * Por eso un alias en cuarentena da {@code Vrfctn=false} con motivo —no hay
 * registro activo, pero tampoco se puede registrar— y el caso multibanco normal
 * da {@code Vrfctn=true} sin motivo: el alias existe y aun así este banco puede
 * registrarlo, porque es la misma persona.
 */
public final class AvailabilityRule {

    private AvailabilityRule() {
    }

    /**
     * @param vrfctn        si existe un registro ACTIVO del alias
     * @param motivo        impedimento para registrarlo, si lo hay
     * @param reasonBic  banco al que se refiere el motivo, cuando revelarlo
     *                      es admisible: sólo para el conflicto en otra entidad,
     *                      que es el único caso en que el banco consultante
     *                      necesita saber que el alias está en otro lado
     */
    public record Resultado(boolean vrfctn, Optional<Reason> reason,
                            Optional<String> reasonBic) {
    }

    /**
     * @param registros registros vigentes (ACTIVO o BLOQUEADO) del alias; vacío
     *                  si el alias no existe o no tiene ninguno
     * @param requestingBic banco que pregunta, sellado por Dispatch
     */
    public static Resultado decide(List<ActiveRegistration> registrations, String requestingBic) {
        if (registrations.isEmpty()) {
            // Ni registrado ni en cuarentena: disponible.
            return new Resultado(false, Optional.empty(), Optional.empty());
        }

        boolean hayActivo = registrations.stream().anyMatch(ActiveRegistration::active);

        // El orden importa. Un conflicto por DPI es definitivo —el alias es de
        // otra persona y no se resuelve esperando— mientras que la cuarentena
        // caduca en 24 h. Si se devolviera primero QUARANTINE, el banco esperaría
        // un día para descubrir después que nunca va a poder registrarlo.
        Optional<ActiveRegistration> conflictoPropio = registrations.stream()
                .filter(r -> r.bic().equals(requestingBic) && !r.sameDpi())
                .findFirst();
        if (conflictoPropio.isPresent()) {
            // El alias está en este mismo banco pero a nombre de otro DPI. Es el
            // caso que el §3.5 no cubría (H-46). No se devuelve el BIC: el banco
            // ya sabe que es él.
            return new Resultado(hayActivo, Optional.of(Reason.ACTIVE_SAME_BANK_DIFF_DPI),
                    Optional.empty());
        }

        Optional<ActiveRegistration> otherBankConflict = registrations.stream()
                .filter(r -> !r.bic().equals(requestingBic) && !r.sameDpi())
                .findFirst();
        if (otherBankConflict.isPresent()) {
            return new Resultado(hayActivo, Optional.of(Reason.ACTIVE_OTHER_BANK_DIFF_DPI),
                    Optional.of(otherBankConflict.get().bic()));
        }

        // De aquí en adelante el DPI coincide en todos los registros vigentes.
        boolean propioActivo = registrations.stream()
                .anyMatch(r -> r.bic().equals(requestingBic) && r.active());
        if (propioActivo) {
            return new Resultado(true, Optional.of(Reason.ACTIVE_SAME_BANK), Optional.empty());
        }

        boolean enCuarentena = registrations.stream().anyMatch(r -> !r.active());
        if (enCuarentena) {
            return new Resultado(hayActivo, Optional.of(Reason.QUARANTINE), Optional.empty());
        }

        // Activo en otra entidad, mismo DPI: es el multibanco normal, no un
        // conflicto. El alias existe (Vrfctn=true) y este banco puede registrarlo
        // igual, así que no lleva motivo. El nombre ACTIVE_OTHER_BANK_DIFF_DPI
        // invita a usarlo aquí y sería un error: no hay DPI distinto.
        return new Resultado(true, Optional.empty(), Optional.empty());
    }
}
