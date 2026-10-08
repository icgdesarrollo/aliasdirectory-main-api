package com.icg.aliasdirectory.mainapi.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Lee la bandeja de salida y publica cada cambio hacia el shard que le toca
 * (PTRAD-862).
 *
 * <p><b>Por qué existe un relay y no se publica directamente.</b> Publicar desde
 * el alta dejaría una ventana entre el commit y el envío: si el proceso cae ahí,
 * el padrón cambió y nadie se enteró. El síntoma no sería un error sino un alias
 * de baja que sigue resolviendo, o uno recién registrado que responde «no
 * existe» (hallazgo H-41). Con la bandeja, el peor caso es publicar dos veces, y
 * eso el consumidor lo resuelve comparando el id del evento.
 *
 * <p><b>Dos instancias a la vez.</b> main-api corre activo-activo. El
 * {@code SKIP LOCKED} de la consulta hace que cada instancia tome un lote
 * distinto en vez de esperarse o duplicar. Lo que NO garantiza es el orden
 * global entre instancias: por eso el mensaje lleva el id y el consumidor debe
 * descartar lo viejo. Ver {@link RegistryEventMessage}.
 *
 * <p><b>Un evento que falla no se pierde ni bloquea.</b> Se anota el error en la
 * fila y se sigue con el siguiente; en la próxima pasada se vuelve a intentar.
 * Es lo contrario de la cola de auditoría, donde un mensaje inválido se descarta
 * a la DLQ: aquí no hay mensajes inválidos —los escribe esta misma aplicación—
 * y lo que falla es el broker, que se arregla solo. Un evento atorado se ve en
 * {@code attempts} y {@code last_error}.
 */
@ConditionalOnProperty(name = "icg.outbox.relay.enabled", havingValue = "true",
        matchIfMissing = true)
@Component
public class RegistryEventRelay {

    private static final Logger log = LoggerFactory.getLogger(RegistryEventRelay.class);

    private final RegistryEventRepository events;
    private final RabbitTemplate rabbit;
    // JsonMapper y no new ObjectMapper(): en Jackson 3 ese constructor quedó
    // obsoleto. El mapper es inmutable y seguro entre hilos.
    private final JsonMapper json = JsonMapper.builder().build();
    private final String exchange;
    private final String routingIndexKey;
    private final int batchSize;
    private final long backlogThreshold;

    public RegistryEventRelay(RegistryEventRepository events, RabbitTemplate rabbit,
            @Value("${icg.outbox.relay.exchange:alias.registry}") String exchange,
            @Value("${icg.outbox.relay.routing-index-key:routing-index}") String routingIndexKey,
            @Value("${icg.outbox.relay.batch-size:200}") int batchSize,
            @Value("${icg.outbox.relay.backlog-threshold:1000}") long backlogThreshold) {
        this.events = events;
        this.rabbit = rabbit;
        this.exchange = exchange;
        this.routingIndexKey = routingIndexKey;
        this.batchSize = batchSize;
        this.backlogThreshold = backlogThreshold;
    }

    /**
     * Una pasada de la bandeja.
     *
     * <p>{@code fixedDelay} y no {@code fixedRate}: con fixedRate, una pasada que
     * tarde más que el intervalo encadena la siguiente de inmediato y, si el
     * broker está lento, se acumulan pasadas en vez de esperar a que se componga.
     *
     * <p>La transacción abarca el lote entero porque los bloqueos del
     * {@code FOR UPDATE} viven hasta el commit: sin ella, las filas se liberarían
     * al terminar la consulta y la otra instancia las tomaría también.
     */
    @Scheduled(fixedDelayString = "${icg.outbox.relay.interval-ms:1000}")
    @Transactional
    public void relay() {
        var lote = events.claimPending(batchSize);
        if (lote.isEmpty()) {
            return;
        }

        int publicados = 0;
        for (var pendiente : lote) {
            try {
                publish(pendiente);
                events.markPublished(pendiente.id());
                publicados++;
            } catch (RuntimeException e) {
                // Se anota y se sigue. No se corta el lote: un evento de un banco
                // cuyo shard tiene problemas no debe frenar los de los otros
                // diecinueve.
                events.markFailed(pendiente.id(), e.toString());
                log.error("evento de padron NO publicado: id={} banco={} operacion={} causa={}",
                        pendiente.id(), pendiente.bankId(), pendiente.operation(), e.toString());
            }
        }

        log.info("relay de padron: tomados={} publicados={}", lote.size(), publicados);
        alertarSiSeAcumula();
    }

    /**
     * Avisa cuando la bandeja crece (PTRAD-866).
     *
     * <p>No es una métrica de volumen: una bandeja que crece significa que hay
     * shards que llevan tiempo sin recibir los cambios, y un shard desfasado no
     * responde despacio, responde mal. Por eso se registra como WARN y con el
     * conteo, que es lo que una alerta puede vigilar.
     */
    private void alertarSiSeAcumula() {
        long pendientes = events.countPending();
        if (pendientes >= backlogThreshold) {
            log.warn("bandeja de salida del padron acumulada: pendientes={} umbral={}."
                    + " Hay shards que pueden estar desfasados", pendientes, backlogThreshold);
        }
    }

    /**
     * La clave de ruteo es el shard del banco dueño, no el del consultante.
     *
     * <p>Es la lectura que hace consistente el reparto 80/20 del padrón: cada
     * Redis guarda sólo los registros de su banco. Mientras H-34 siga abierto,
     * esto decide únicamente a dónde va la ACTUALIZACIÓN, no cómo rutea Dispatch
     * una consulta; son dos particionamientos que pueden no coincidir.
     */
    private void publish(RegistryEventRepository.Pending p) {
        var mensaje = new RegistryEventMessage(
                p.id(), p.aliasId(), p.bankId(), p.registrationId(), p.operation(),
                p.affectsRouting(), p.originMsgId(), p.eventAt().getTime());
        byte[] cuerpo = json.writeValueAsBytes(mensaje);

        enviar(p.shard(), cuerpo);

        // Y al índice global cuando cambia el CONJUNTO de bancos del alias
        // (PTRAD-864). Son dos destinos y no uno con una bandera: el shard y el
        // índice de ruteo son dos almacenes distintos, con dueños distintos y que
        // se pueden reconstruir por separado. Obligar a cada cargador de shard a
        // leer affects_routing y decidir si además toca el índice repartiría esa
        // responsabilidad entre veinte consumidores, y bastaría que uno lo
        // ignorara para que el índice quedara desfasado sin que nada lo notara.
        //
        // El mismo mensaje, con otra clave. El id es el mismo, así que los dos
        // destinos pueden descartar lo que ya aplicaron por el mismo criterio.
        if (p.affectsRouting()) {
            enviar(routingIndexKey, cuerpo);
        }
    }

    private void enviar(String routingKey, byte[] cuerpo) {
        rabbit.convertAndSend(exchange, routingKey, cuerpo, m -> {
            m.getMessageProperties().setContentType("application/json");
            m.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return m;
        });
    }

    /** Sólo para el log de arranque: deja constancia de a dónde publica. */
    @Override
    public String toString() {
        return "RegistryEventRelay[" + exchange + ", lote=" + batchSize + "]";
    }
}
