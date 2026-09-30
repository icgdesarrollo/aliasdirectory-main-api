package com.icg.aliasdirectory.mainapi.availability;

/**
 * Un registro vigente del alias, tal como lo necesita la regla de disponibilidad.
 *
 * <p>Deliberadamente NO trae el IBAN, el nombre del titular ni el IdCliente. La
 * consulta de disponibilidad responde si el alias se puede registrar; no es un
 * canal para que un banco averigüe los datos de cuenta de un cliente de otro. Lo
 * que no se lee no se puede filtrar por error en una traza o en una respuesta.
 *
 * @param bic      banco dueño del registro
 * @param activo   true si está ACTIVO; false si está BLOQUEADO (en cuarentena)
 * @param sameDpi si el DPI del titular del registro coincide con el consultado
 */
public record ActiveRegistration(String bic, boolean active, boolean sameDpi) {
}
