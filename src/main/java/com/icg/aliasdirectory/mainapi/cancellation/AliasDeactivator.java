package com.icg.aliasdirectory.mainapi.cancellation;

import com.icg.aliasdirectory.mainapi.integrity.IntegritySeal;
import com.icg.aliasdirectory.mainapi.registration.RegistryWriteRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Los tres pasos indivisibles de una baja: cambiar el estado, dejar histórico y
 * volver a sellar.
 *
 * <p>Vive aparte de {@link CancellationService} porque hay dos caminos que dan
 * de baja el mismo registro y deben hacerlo idénticamente: el banco por ISO
 * 20022 (F5) y el portal de Call Center tras una aprobación. Duplicar estos
 * pasos en el portal sería duplicar la regla de cuarentena y el resello, y la
 * copia se quedaría atrás en cuanto uno de los dos cambie.
 *
 * <p>El resello no es opcional: {@code status} entra en el sello de integridad,
 * así que una baja que lo cambie sin recalcularlo deja el registro marcado como
 * alterado y la siguiente consulta de ese alias falla la verificación (H-57).
 *
 * <p>No abre transacción. La abre quien llama, porque el alcance correcto es la
 * operación completa —que en ALL_BANKS son varios registros, y en el portal
 * incluye además cerrar la solicitud— y no cada registro suelto.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Component
public class AliasDeactivator {

    private final CancellationRepository cancellations;
    private final RegistryWriteRepository registry;
    private final IntegritySeal seal;

    public AliasDeactivator(CancellationRepository cancellations, RegistryWriteRepository registry,
            IntegritySeal seal) {
        this.cancellations = cancellations;
        this.registry = registry;
        this.seal = seal;
    }

    /**
     * Por dónde entró la baja y, si fue por el portal, quién la ejecutó.
     *
     * <p>Van juntos porque el histórico los necesita juntos: un canal sin
     * persona no dice quién decidió, y una persona sin canal no dice cómo llegó.
     */
    public record Origin(String channel, Long portalUserId) {

        /** La pidió un banco por ISO 20022: no hay agente detrás. */
        public static Origin api() {
            return new Origin("API", null);
        }

        /** La aprobó un agente en el portal de Call Center. */
        public static Origin portal(long portalUserId) {
            return new Origin("PORTAL", portalUserId);
        }
    }

    /**
     * @param scope        OWN_BANK o ALL_BANKS, para el histórico
     * @param msgId        identificador de la solicitud que la originó
     * @param originBankId entidad que pidió la baja, que no siempre es la dueña
     *                     del registro (ALL_BANKS)
     * @param origin       canal y agente, para que la auditoría pueda separar
     *                     una baja del banco de una de Call Center
     */
    public QuarantineRule.Outcome deactivate(CancellationRepository.Registration registration,
            Instant now, String scope, String msgId, int originBankId, Origin origin) {

        var outcome = QuarantineRule.decide(registration.registeredAt().toInstant(), now);
        String reason = outcome.reason() == null ? null : outcome.reason().name();

        cancellations.cancel(registration.id(), outcome.status(), outcome.quarantineUntil(),
                reason, now);
        cancellations.recordHistory(registration.id(), registration.status(), outcome.status(),
                reason, scope, origin.channel(), originBankId, origin.portalUserId(), msgId);

        var fields = new IntegritySeal.SealedFields(
                registration.aliasUuid(), registration.regnId(), registration.bankId(),
                outcome.status(), registration.nameDisplayLevel(), registration.aliasBidx(),
                registration.dpiBidx(), registration.ibanEnc());
        var fingerprint = seal.compute(fields);
        registry.seal(registration.id(), fingerprint.bytes(), fingerprint.version());

        return outcome;
    }
}
