package com.icg.aliasdirectory.mainapi.outbox;

/**
 * Qué le pasó al padrón, en los términos en que la caché tiene que reaccionar.
 *
 * <p>Son exactamente los valores del ENUM {@code registry_event.operation}. Se
 * declaran aquí y no como cadenas sueltas porque el valor viaja a la base, donde
 * el ENUM lo rechaza, y de ahí al mensaje que lee el cargador del shard: un
 * literal mal escrito en cualquiera de los dos sitios falla en tiempo de
 * ejecución y no de compilación.
 *
 * <p>De los seis, el código sólo produce tres hoy. Los otros existen en el
 * esquema porque el diseño del padrón en memoria los contempla, y se dejan
 * declarados para que quien los implemente no tenga que decidir cómo se llaman:
 *
 * <ul>
 *   <li>{@link #LIBERACION} — la espera el proceso que vence la cuarentena
 *       ({@code BLOQUEADO} → {@code INACTIVO}), que no existe.
 *   <li>{@link #CAMBIO_CUENTA} — no hay operación que cambie la cuenta de un
 *       registro vigente.
 *   <li>{@link #RECARGA_SHARD} — es operativa: la emite quien decide reconstruir
 *       un shard entero desde MySQL, no el flujo de un alias.
 * </ul>
 */
public enum RegistryOperation {

    /** Alias registrado en un banco donde no lo estaba. */
    ALTA,

    /** El registro salió del padrón vigente: {@code INACTIVO}. */
    BAJA,

    /**
     * El registro quedó en cuarentena: {@code BLOQUEADO}.
     *
     * <p>No es una baja. El alias sigue perteneciendo a ese banco y el shard
     * tiene que seguir conociéndolo para poder responder {@code QUARANTINE}; lo
     * que cambia es que deja de resolver.
     */
    BLOQUEO,

    /** Venció la cuarentena y el registro pasó a {@code INACTIVO}. */
    LIBERACION,

    /** El registro vigente apunta ahora a otra cuenta del mismo banco. */
    CAMBIO_CUENTA,

    /** Orden operativa: reconstruir el shard de un banco desde MySQL. */
    RECARGA_SHARD
}
