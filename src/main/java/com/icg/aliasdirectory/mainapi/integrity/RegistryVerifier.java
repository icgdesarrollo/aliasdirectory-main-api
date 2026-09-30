package com.icg.aliasdirectory.mainapi.integrity;

import com.icg.aliasdirectory.mainapi.registration.RegistryWriteRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Comprueba el sello de los registros de un alias antes de responder sobre él.
 *
 * <p>Es el lado de lectura de H-57. El sello se calcula al registrar; acá es
 * donde sirve de algo.
 *
 * <p><strong>Qué hace cuando encuentra una fila alterada.</strong> Lanza, y por
 * tanto la consulta falla. Es una decisión con un costo que conviene tener
 * presente: quien pueda corromper filas puede dejar sin servicio a esos alias.
 * La alternativa —responder igual y sólo anotar la sospecha— convierte el
 * control en decorativo, porque el caso que motivó todo esto es justamente que
 * la respuesta lleve un IBAN que no es el del titular. Entre negar el servicio a
 * un alias y dar por buena una cuenta que alguien cambió por debajo, se elige lo
 * primero. La decisión final es de Ciberseguridad y está anotada como tal.
 *
 * <p>El registro de la alerta no lleva el alias ni el DPI (regla T-8): lleva el
 * {@code RegnId}, que es un identificador del trámite y permite encontrar la
 * fila sin exponer al titular.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Component
public class RegistryVerifier {

    private static final Logger log = LoggerFactory.getLogger(RegistryVerifier.class);

    private final RegistryWriteRepository registry;
    private final IntegritySeal seal;

    public RegistryVerifier(RegistryWriteRepository registry, IntegritySeal seal) {
        this.registry = registry;
        this.seal = seal;
    }

    /**
     * Verifica todos los registros vigentes del alias.
     *
     * @throws CompromisedIntegrityException si alguno no coincide con su sello
     */
    public void verify(String tipo, byte[] aliasBidx) {
        for (var fila : registry.sealedFields(tipo, aliasBidx)) {
            var resultado = seal.verify(fila.fields(), fila.seal());
            switch (resultado) {
                case INTEGRA -> { }
                case SIN_SELLO -> log.warn(
                        "registro sin sello de integridad: regnId={} - anterior a H-57,"
                        + " no se puede afirmar que esté íntegro", fila.fields().regnId());
                case ALTERADA -> {
                    // A nivel ERROR y con el RegnId: esto no es un error de la
                    // consulta, es alguien escribiendo en la base por fuera de la
                    // aplicación. Quien lea este log tiene que poder ir a la fila.
                    log.error("INTEGRIDAD COMPROMETIDA: regnId={} banco={} - la fila no"
                            + " coincide con su sello", fila.fields().regnId(),
                            fila.fields().bankId());
                    throw new CompromisedIntegrityException(fila.fields().regnId());
                }
            }
        }
    }

    /** Un registro del padrón no coincide con su sello de integridad. */
    public static class CompromisedIntegrityException extends RuntimeException {
        public CompromisedIntegrityException(String regnId) {
            super("El registro " + regnId + " fue alterado fuera de la aplicación");
        }
    }
}
