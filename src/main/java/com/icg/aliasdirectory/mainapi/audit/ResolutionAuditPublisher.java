package com.icg.aliasdirectory.mainapi.audit;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

import tools.jackson.databind.json.JsonMapper;

/**
 * Publica el evento de auditoría de una consulta (PTRAD-705).
 *
 * <p><b>Por qué cifra aquí y no en el consumidor.</b> Lo cómodo sería mandar el
 * alias y el IdCliente en claro y que el consumidor los cifrara: el camino
 * caliente no pagaría nada. Pero entonces el mensaje con el alias y el DPI de una
 * persona quedaría escrito en una cola persistente, con reintentos y una DLQ que
 * puede retenerlo días. Es el mismo dato que la regla T-8 prohíbe en un log, en un
 * sitio donde dura más. Así que se cifra antes de publicar.
 *
 * <p><b>Lo que cuesta.</b> Un solo {@code encrypt} en lote con los tres valores,
 * más un {@code hmac} cuando hay IdCliente. Medido en E19-D01 contra OpenBao real,
 * un lote son 0.71 ms: menos del 1 % del SLA de 100 ms. Hacerlo suelto serían
 * 4.84 ms por valor, que es como se gasta el presupuesto sin notarlo.
 *
 * <p><b>Nunca tumba la consulta.</b> Si el KMS o el broker fallan, se registra el
 * fallo y la resolución responde igual. Es deliberado y es la diferencia con la
 * bitácora del portal, que sí es fail-closed: allí el usuario puede reintentar,
 * aquí hay una transferencia esperando del otro lado. Un fallo de auditoría se
 * convierte en un hueco en la bitácora —detectable, y por eso se registra en el
 * log— y no en una transferencia que no se puede hacer.
 */
/*
 * Las DOS propiedades tienen que estar en true. La del KMS no es una precaución
 * de más: este publicador necesita TransitClient, que sólo existe con
 * icg.kms.enabled=true, y sin esa condición una compilación recién clonada
 * —donde el KMS está apagado por omisión— no arrancaría, porque Spring crearía
 * el publicador y no encontraría a quién inyectarle. El alias viaja cifrado o no
 * viaja: publicarlo en claro cuando no hay KMS sería dejar el dato en una cola
 * persistente, que es justo lo que la regla T-8 evita.
 */
@ConditionalOnProperty(name = {"icg.kms.enabled", "icg.audit.resolution.enabled"},
        havingValue = "true")
@Component
public class ResolutionAuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(ResolutionAuditPublisher.class);

    private final RabbitTemplate rabbit;
    private final TransitClient kms;
    private final BlindIndex index;
    // JsonMapper y no new ObjectMapper(): en Jackson 3 ese constructor quedó
    // obsoleto. El mapper es inmutable y seguro entre hilos.
    private final JsonMapper json;
    private final String exchange;
    private final String routingKey;
    private final String encryptionKey;
    private final String originNode;

    public ResolutionAuditPublisher(RabbitTemplate rabbit, TransitClient kms, BlindIndex index,
            @Value("${icg.audit.resolution.exchange:alias.audit}") String exchange,
            @Value("${icg.audit.resolution.routing-key:resolution}") String routingKey,
            @Value("${icg.kms.encryption-key:dir-alias-pii}") String encryptionKey,
            @Value("${icg.audit.origin-node:${HOSTNAME:local}}") String originNode) {
        this.rabbit = rabbit;
        this.kms = kms;
        this.index = index;
        this.json = JsonMapper.builder().build();
        this.exchange = exchange;
        this.routingKey = routingKey;
        this.encryptionKey = encryptionKey;
        this.originNode = originNode;
    }

    /**
     * @param alias            alias TAL COMO lo envió el banco, sin normalizar. Es
     *                         deliberado: {@code alias_enc} es la prueba de qué se
     *                         preguntó, y normalizarlo antes de cifrarlo guardaría
     *                         lo que el directorio entendió en vez de lo que el
     *                         banco mandó. La búsqueda va por la huella, que sí
     *                         está normalizada
     * @param aliasBidx        su huella, que el servicio ya calculó para buscar
     * @param originatorCustId IdCliente originador en claro, o null si no aplica
     * @param amqpsQueue       cola por la que entró la petición, o null si no entró
     *                         por cola. De aquí sale {@code channel}: hoy todas las
     *                         operaciones ISO llegan por AMQPS desde Dispatch, pero
     *                         fijar el canal aquí haría que el día que exista una
     *                         entrada REST la bitácora siguiera diciendo AMQPS sin
     *                         que nadie lo note
     */
    public void publish(String evtAlias, String method, String alias, byte[] aliasBidx,
            String originatorCustId, String requestingBic, String amqpsQueue,
            String msgId, String msgIdRpt,
            boolean vrfctn, String reasonPrtry, int httpStatus, int returnedRecords,
            long latencyMs, String correlationId, List<ResolutionAuditEvent.Detail> detail) {
        try {
            // Un solo viaje al KMS para los tres valores. El orden del lote es el
            // orden de la respuesta: Transit lo garantiza y TransitClient lo
            // mantiene.
            boolean conCliente = originatorCustId != null && !originatorCustId.isBlank();
            List<String> claros = conCliente
                    ? List.of(alias, originatorCustId, requestingBic)
                    : List.of(alias, requestingBic);
            List<String> cifrados = kms.encrypt(encryptionKey, claros);

            String orgtrEnc = conCliente ? cifrados.get(1) : null;
            String bancoEnc = conCliente ? cifrados.get(2) : cifrados.get(1);

            String orgtrBidx = null;
            Integer orgtrVer = null;
            if (conCliente) {
                byte[] huella = index.ofCustomerId(originatorCustId);
                orgtrBidx = Base64.getEncoder().encodeToString(huella);
                orgtrVer = index.version();
            }

            // returned_evt_alias lo decide el método, no el que llama: lo exige
            // ck_resolution_event_returned_evt y es una regla del contrato, no una
            // opción. En disponibilidad el EvtAlias es interno y no viaja.
            boolean devuelveEvtAlias = ResolutionAuditEvent.RESOLUCION.equals(method);

            var evento = new ResolutionAuditEvent(
                    evtAlias, System.currentTimeMillis(), msgId, msgIdRpt,
                    amqpsQueue == null ? "REST" : "AMQPS", method,
                    cifrados.get(0), Base64.getEncoder().encodeToString(aliasBidx),
                    index.version(), orgtrEnc, orgtrBidx, orgtrVer, bancoEnc,
                    vrfctn, reasonPrtry, httpStatus, returnedRecords, devuelveEvtAlias,
                    (int) latencyMs, null, amqpsQueue, originNode, correlationId,
                    detail == null ? List.of() : detail);

            rabbit.convertAndSend(exchange, routingKey,
                    json.writeValueAsBytes(evento), m -> {
                        m.getMessageProperties().setContentType("application/json");
                        // Persistente: si el broker se reinicia con la cola llena, los
                        // eventos siguen ahí (PTRAD-707).
                        m.getMessageProperties().setDeliveryMode(
                                org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                        return m;
                    });
        } catch (RuntimeException e) {
            // Se registra el EvtAlias y el método, nunca el alias ni el IdCliente
            // (regla T-8). Con el EvtAlias se cruza este hueco contra el log de
            // acceso para saber qué consulta se quedó sin auditar.
            log.error("auditoria de resolucion NO publicada: evtAlias={} metodo={} causa={}",
                    evtAlias, method, e.toString());
        }
    }

    /** Sólo para el log de arranque: deja constancia de a dónde publica. */
    @Override
    public String toString() {
        return "ResolutionAuditPublisher[" + exchange + "/" + routingKey + "]";
    }
}
