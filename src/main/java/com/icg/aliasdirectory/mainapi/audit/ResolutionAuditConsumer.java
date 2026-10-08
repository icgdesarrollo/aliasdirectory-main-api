package com.icg.aliasdirectory.mainapi.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Persiste la auditoría de resolución (PTRAD-706).
 *
 * <p>Va en su propia cola y no en la de peticiones a propósito: la resolución
 * tiene un SLA de 100 ms y la auditoría no tiene ninguno. Compartir cola haría que
 * un pico de auditoría retrasara transferencias.
 *
 * <p><b>Qué pasa cuando falla.</b> Hay dos clases de fallo y no se tratan igual:
 *
 * <ul>
 *   <li><b>El mensaje está mal</b> —JSON ilegible, campo que no cumple un CHECK—:
 *       reintentarlo no lo va a arreglar. Se rechaza sin reencolar y cae en la DLQ
 *       de una vez, para que no ocupe la cola bloqueando a los que sí se pueden
 *       escribir.
 *   <li><b>La base no responde</b>: se deja propagar la excepción para que
 *       RabbitMQ reencole. Tras {@code x-delivery-limit} intentos el broker lo
 *       manda a la DLQ por su cuenta (PTRAD-707).
 * </ul>
 *
 * <p>La diferencia importa: sin ella, un solo mensaje corrupto se reencola para
 * siempre y la bitácora deja de escribirse entera.
 */
@ConditionalOnProperty(name = "icg.audit.resolution.enabled", havingValue = "true",
        matchIfMissing = true)
@Component
public class ResolutionAuditConsumer {

    private static final Logger log = LoggerFactory.getLogger(ResolutionAuditConsumer.class);

    private final ResolutionAuditRepository repository;
    // JsonMapper y no new ObjectMapper(): en Jackson 3 ese constructor quedó
    // obsoleto. El mapper es inmutable y seguro entre hilos.
    private final JsonMapper json = JsonMapper.builder().build();

    public ResolutionAuditConsumer(ResolutionAuditRepository repository) {
        this.repository = repository;
    }

    @RabbitListener(queues = "${icg.audit.resolution.queue:alias.audit.resolution}")
    public void handle(byte[] body) {
        ResolutionAuditEvent evento;
        try {
            evento = json.readValue(body, ResolutionAuditEvent.class);
        } catch (RuntimeException e) {
            // A la DLQ directo: un mensaje que no se puede leer no mejora con el
            // tiempo. Se registra el tamaño y nunca el cuerpo, que lleva
            // criptogramas y podría llevar algo más (regla T-8).
            log.error("mensaje de auditoria ilegible, va a la DLQ: bytes={} causa={}",
                    body.length, e.toString());
            throw new AmqpRejectAndDontRequeueException("auditoria ilegible", e);
        }

        try {
            repository.save(evento);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Violación de un CHECK o de un tipo: el mensaje es inválido contra el
            // esquema y reintentarlo da el mismo error. También a la DLQ.
            log.error("evento de auditoria rechazado por el esquema: evtAlias={} metodo={}"
                    + " causa={}", evento.evtAlias(), evento.method(), e.getMostSpecificCause());
            throw new AmqpRejectAndDontRequeueException("auditoria invalida", e);
        }
        // Cualquier otra excepción —la base caída, un bloqueo— se deja propagar:
        // ésas sí se arreglan reintentando, y de eso se encarga el broker.
    }
}
