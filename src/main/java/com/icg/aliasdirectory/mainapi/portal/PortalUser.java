package com.icg.aliasdirectory.mainapi.portal;

import java.util.Set;

/**
 * El operador autenticado, resuelto contra el padrón.
 *
 * @param id       portal_user.id, que es lo que referencian las solicitudes de baja
 * @param bankId   entidad del operador; null identifica a un usuario de ICG (ámbito global)
 * @param bic      BIC de esa entidad, null para ICG
 * @param username login desnormalizado en la bitácora
 */
public record PortalUser(long id, Integer bankId, String bic, String username, String fullName,
        Set<String> permissions) {

    public boolean can(String permission) {
        return permissions.contains(permission);
    }

    /** Un usuario de ICG no tiene entidad, así que no puede dar de baja por OWN_BANK. */
    public boolean belongsToBank() {
        return bankId != null;
    }
}
