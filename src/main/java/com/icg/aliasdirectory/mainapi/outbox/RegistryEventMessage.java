package com.icg.aliasdirectory.mainapi.outbox;

/**
 * Lo que el relay publica para que un shard de caché se actualice.
 *
 * <p><b>No lleva ningún dato del titular.</b> Ni el alias, ni el DPI, ni la
 * cuenta, ni cifrados. Es deliberado y es la diferencia con la bitácora de
 * consultas: este mensaje sólo dice <em>qué cambió y dónde</em>, y quien lo
 * consume ya tiene acceso al padrón para releer lo que necesite. Un mensaje con
 * el registro completo ahorraría esa lectura y pondría el padrón a viajar por
 * una cola, que es exactamente lo que E19-D01 concluyó que no debe pasar.
 *
 * <p><b>El {@code eventId} es el orden.</b> Viene del AUTO_INCREMENT de
 * {@code registry_event} y es creciente por construcción. El consumidor debe
 * descartar un evento cuyo id sea menor que el último aplicado para ese alias:
 * con dos instancias de main-api relevando a la vez, dos eventos del mismo alias
 * pueden salir en orden distinto al de la tabla. Sin esa comprobación, una baja
 * seguida de un alta podría aplicarse al revés y dejar el shard diciendo que un
 * alias vigente no existe.
 *
 * @param eventId         id de la fila en {@code registry_event}; es el orden
 * @param aliasId         alias afectado
 * @param bankId          banco dueño del registro, el que identifica el shard
 * @param registrationId  registro concreto, o null si la operación no es de uno
 * @param operation       qué pasó
 * @param affectsRouting  si además hay que rehacer el índice global de ruteo
 * @param originMsgId     MsgId de la solicitud que lo provocó, para rastrearlo
 * @param eventAtMillis   momento del cambio, en epoch-millis
 */
public record RegistryEventMessage(
        long eventId,
        long aliasId,
        int bankId,
        Long registrationId,
        String operation,
        boolean affectsRouting,
        String originMsgId,
        long eventAtMillis) {
}
