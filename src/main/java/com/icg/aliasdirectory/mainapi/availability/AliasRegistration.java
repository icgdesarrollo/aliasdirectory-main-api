package com.icg.aliasdirectory.mainapi.availability;

/**
 * Un registro vigente del alias, sin comparar DPI.
 *
 * <p>Es lo que ve Disponibilidad Resolución. Deliberadamente NO trae el campo
 * {@code sameDpi} de {@link ActiveRegistration}: esa consulta no lleva DPI de
 * entrada, así que no hay nada contra qué comparar. Tener el campo y dejarlo en
 * false sería peor que no tenerlo —invitaría a que alguien lo leyera creyendo
 * que significa «el DPI no coincide»— y de ahí saldría un
 * ACTIVE_OTHER_BANK_DIFF_DPI que este método tiene prohibido devolver.
 *
 * @param bic    banco dueño del registro
 * @param activo true si está ACTIVO; false si está BLOQUEADO (en cuarentena)
 */
public record AliasRegistration(String bic, boolean active) {
}
