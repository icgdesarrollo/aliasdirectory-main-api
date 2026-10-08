package com.icg.aliasdirectory.mainapi.outbox;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * La bandeja de salida del padrón: {@code registry_event}.
 *
 * <p>Separa dos responsabilidades que no se parecen en nada. {@link #append} la
 * llama el alta y la baja, <b>dentro de su transacción</b>; el resto lo llama el
 * relay, en la suya. Que compartan clase es sólo que comparten tabla.
 *
 * <p><b>Por qué el evento se escribe en la misma transacción.</b> La alternativa
 * obvia —dar de alta y después publicar a RabbitMQ— tiene una ventana en la que
 * el alta quedó escrita y la publicación no llegó a salir: el proceso se cae, la
 * transacción ya hizo commit y nadie se entera nunca. El shard se queda diciendo
 * que un alias vigente no existe, y eso no se nota como un error sino como un
 * cliente al que le rechazan una transferencia. Escribir la fila dentro de la
 * transacción convierte ese riesgo en otro muy distinto: el evento puede
 * publicarse dos veces, que el consumidor resuelve comparando el id.
 */
@Repository
public class RegistryEventRepository {

    private static final String APPEND = """
            INSERT INTO registry_event
                   (alias_id, bank_id, alias_registration_id, operation,
                    affects_routing, origin_msg_id)
            VALUES (:aliasId, :bankId, :registrationId, :operation,
                    :affectsRouting, :originMsgId)
            """;

    /**
     * Toma un lote de pendientes y lo reserva para esta instancia.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} es lo que permite que las dos instancias
     * de main-api releven a la vez sin pisarse: la segunda salta las filas que la
     * primera tiene tomadas en vez de esperarlas. Sin {@code SKIP LOCKED} el
     * relay de un centro de datos quedaría bloqueado detrás del otro, y sin
     * {@code FOR UPDATE} las dos publicarían el mismo evento.
     *
     * <p>El orden por {@code id} es el orden en que ocurrieron los cambios. Entre
     * instancias no está garantizado, y por eso el mensaje lleva el id: ver
     * {@link RegistryEventMessage}.
     *
     * <p>La clave de ruteo sale de {@code participant_bank.cache_shard}. Si un
     * banco no lo tiene definido se usa su BIC, que siempre existe: un evento sin
     * destino no se publica, y un evento que no se publica se acumula.
     */
    private static final String CLAIM_PENDING = """
            SELECT e.id              AS id,
                   e.alias_id        AS alias_id,
                   e.bank_id         AS bank_id,
                   e.alias_registration_id AS registration_id,
                   e.operation       AS operation,
                   e.affects_routing AS affects_routing,
                   e.origin_msg_id   AS origin_msg_id,
                   e.event_at        AS event_at,
                   COALESCE(b.cache_shard, b.bicfi) AS shard
              FROM registry_event e
              JOIN participant_bank b ON b.id = e.bank_id
             WHERE e.published_at IS NULL
               AND (e.next_attempt_at IS NULL
                    OR e.next_attempt_at <= CURRENT_TIMESTAMP(3))
             ORDER BY e.id
             LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """;

    private static final String MARK_PUBLISHED = """
            UPDATE registry_event
               SET published_at    = CURRENT_TIMESTAMP(3),
                   last_error      = NULL,
                   next_attempt_at = NULL
             WHERE id = :id
            """;

    /**
     * Deja constancia del intento fallido sin marcarlo publicado, y lo aparta
     * hasta que le toque reintentar (PTRAD-865).
     *
     * <p><b>El retroceso.</b> {@code 2^intentos} segundos, acotado a 300: 2 s, 4,
     * 8… hasta cinco minutos a partir del noveno fallo. Sin él, un evento que
     * falla se reintentaría cada segundo —3 600 veces por hora— contra un broker
     * que ya está en problemas, y el lote de cada pasada se llenaría de los
     * mismos eventos rotos mientras los sanos esperan detrás.
     *
     * <p>El tope de 1000 en {@code attempts} no es decorativo: lo exige
     * {@code ck_registry_event_attempts}, y pasarse abortaría el UPDATE entero,
     * con lo que el evento perdería también el mensaje de error — que es lo único
     * que explica por qué lleva ahí desde ayer. Por eso el {@code LEAST} está en
     * las dos partes: en el contador y en el exponente.
     */
    private static final String MARK_FAILED = """
            UPDATE registry_event
               SET attempts        = LEAST(attempts + 1, 1000),
                   last_error      = LEFT(:error, 500),
                   next_attempt_at = CURRENT_TIMESTAMP(3)
                       + INTERVAL LEAST(POWER(2, LEAST(attempts + 1, 9)), 300) SECOND
             WHERE id = :id
            """;

    private static final String COUNT_PENDING = """
            SELECT COUNT(*) FROM registry_event WHERE published_at IS NULL
            """;

    private final JdbcClient jdbc;

    public RegistryEventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Anota el cambio. <b>No abre transacción</b>: se une a la de quien llama,
     * que es el punto de todo esto.
     *
     * @param registrationId puede ser null si el cambio no es de un registro
     */
    public void append(long aliasId, int bankId, Long registrationId,
            RegistryOperation operation, boolean affectsRouting, String originMsgId) {
        jdbc.sql(APPEND)
                .param("aliasId", aliasId)
                .param("bankId", bankId)
                .param("registrationId", registrationId)
                .param("operation", operation.name())
                .param("affectsRouting", affectsRouting)
                .param("originMsgId", originMsgId)
                .update();
    }

    public List<Pending> claimPending(int limit) {
        return jdbc.sql(CLAIM_PENDING).param("limit", limit).query(Pending.class).list();
    }

    public void markPublished(long id) {
        jdbc.sql(MARK_PUBLISHED).param("id", id).update();
    }

    public void markFailed(long id, String error) {
        jdbc.sql(MARK_FAILED).param("id", id).param("error", error).update();
    }

    public long countPending() {
        return jdbc.sql(COUNT_PENDING).query(Long.class).single();
    }

    /** Una fila tomada por el relay, ya resuelta a qué shard va. */
    public record Pending(long id, long aliasId, int bankId, Long registrationId,
            String operation, boolean affectsRouting, String originMsgId,
            java.sql.Timestamp eventAt, String shard) {
    }
}
