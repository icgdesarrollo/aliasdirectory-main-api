package com.icg.aliasdirectory.mainapi.availability;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decide si un alias es resoluble, sin DPI de por medio.
 *
 * <p>Función pura, como la de registro. La diferencia con aquélla no es de
 * grado: aquí la consulta no trae DPI, así que los dos motivos que se calculan
 * comparando DPI —ACTIVE_SAME_BANK y ACTIVE_OTHER_BANK_DIFF_DPI— no se pueden
 * calcular ni devolver. El perfil XSD de la respuesta ya lo impide; esta clase
 * lo respeta por construcción, sin depender de que el esquema atrape el error.
 *
 * <p>Y el significado de {@code Vrfctn} cambia respecto de registro. Allá
 * respondía «¿existe un registro activo?»; aquí responde la pregunta que hace el
 * banco: <b>¿puedo resolver este alias para transferir?</b> Un alias en
 * cuarentena existe y no es resoluble, así que va false con motivo. Uno que no
 * existe va false sin motivo.
 */
public final class ResolvabilityRule {

    private ResolvabilityRule() {
    }

    /**
     * @param vrfctn  si el alias es resoluble ahora
     * @param motivo  QUARANTINE, o vacío. Es el único motivo posible aquí
     * @param ownerBic banco al que resolvería, sólo cuando es inequívoco
     */
    public record Resultado(boolean vrfctn, Optional<Reason> reason,
                            Optional<String> ownerBic) {
    }

    public static Resultado decide(List<AliasRegistration> registrations) {
        if (registrations.isEmpty()) {
            // No existe. Sin motivo: los motivos explican por qué un alias que
            // existe no se puede usar, no la ausencia.
            return new Resultado(false, Optional.empty(), Optional.empty());
        }

        Set<String> bancosActivos = registrations.stream()
                .filter(AliasRegistration::active)
                .map(AliasRegistration::bic)
                .collect(Collectors.toSet());

        if (bancosActivos.isEmpty()) {
            // Existe, pero todos sus registros están bloqueados: cuarentena.
            return new Resultado(false, Optional.of(Reason.QUARANTINE), Optional.empty());
        }

        // Resoluble. Se nombra el banco SÓLO si hay uno: con el mismo alias
        // vigente en varias entidades, elegir una sería arbitrario y el banco
        // podría tomarlo como destino de la transferencia. Quién resuelve a qué
        // cuenta lo contesta F3, que devuelve la lista completa; esta consulta
        // sólo dice que se puede resolver. Además el perfil admite un único
        // UpdtdPtyAndAcctId por Rpt, así que ni siquiera cabrían las dos.
        return new Resultado(true, Optional.empty(),
                bancosActivos.size() == 1 ? bancosActivos.stream().findFirst() : Optional.empty());
    }
}
