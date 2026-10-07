package com.icg.aliasdirectory.mainapi.portal;

import java.util.List;
import java.util.Set;

/**
 * Los cuerpos JSON del BFF. Son del portal y de nadie más: los bancos hablan
 * ISO 20022 contra Dispatch, y mezclar ambos contratos en los mismos tipos
 * ataría el esquema del portal a un cambio de ISO y al revés.
 */
public final class PortalDto {

    public record Me(String username, String fullName, String bic, Set<String> permissions) {
    }

    /**
      * Un registro tal como el portal lo muestra (Anexo §8.3).
      *
      * @param accountMasked la cuenta SIEMPRE enmascarada. El campo se llama así
      *                      y no «iban» a propósito: quien lo lea en el código no
      *                      debe poder confundirlo con el valor completo.
      */
    public record AliasRegistrationView(String aliasUuid, String bic, String bankName,
            String accountMasked, String accountType, String currency, String status,
            String registeredAt, boolean ownEntity) {
    }

    /**
     * @param criterion por dónde se entró: ALIAS, CUENTA, DPI o CLIENTE. El
     *                  valor consultado NO viaja de vuelta: el navegador ya lo
     *                  tiene y repetirlo sólo lo pondría además en la respuesta.
     */
    public record AliasLookup(String criterion, String outcome, String eventId,
            List<AliasRegistrationView> registrations) {
    }

    public record CancellationRequestInput(String aliasUuid, String reason) {
    }

    public record CancellationRejectInput(String reason) {
    }

    public record CancellationView(long id, String aliasUuid, String aliasMasked, String bic,
            String bankName, String status, String reason, String requestedBy, String requestedAt,
            String resolvedBy, String resolvedAt, String resolutionReason) {
    }

    public record ErrorView(String code, String message, List<String> details) {
    }

    // ---- administración delegada de agentes (E15-D04) ---------------------

    /**
     * Alta de un agente. La entidad NO viaja aquí: es la del administrador que
     * ejecuta el alta (regla T-1). Un campo de banco en el cuerpo sería una
     * invitación a que un administrador diera de alta gente en otra entidad.
     */
    public record UserCreateInput(String username, String fullName, String email,
            List<String> roles) {
    }

    public record UserUpdateInput(String fullName, String email) {
    }

    public record UserStatusInput(String status, String reason) {
    }

    public record UserRolesInput(List<String> roles) {
    }

    public record PortalUserView(long id, String username, String fullName, String email,
            String bic, String status, String enrolledAt, String lastAccessAt, String createdBy,
            List<String> roles) {
    }

    /**
     * Resultado del alta.
     *
     * @param temporaryPassword se devuelve UNA sola vez, para que el
     *                          administrador se la entregue al agente. No queda
     *                          en la bitácora ni en el log, y Keycloak obliga a
     *                          cambiarla en el primer ingreso.
     */
    public record UserCreatedView(long id, String username, String temporaryPassword) {
    }

    public record RoleView(String code, String name, String description, String scope) {
    }

    // ---- bitácora (E15-D05, Anexo §8.4) ----------------------------------

    public record AuditFilter(java.time.LocalDate from, java.time.LocalDate to, String userLogin,
            String operation, Integer limit) {
    }

    /**
     * Una línea de bitácora como la ve el portal.
     *
     * @param entityRef identificador no reversible del objeto afectado
     * @param detail    JSON ya enmascarado en origen; se devuelve tal cual
     */
    public record AuditView(java.time.LocalDateTime occurredAt, String userLogin, String bic,
            String ip, String operation, String entity, String entityRef, String result,
            Integer httpStatus, String detail, String correlationId) {
    }

    private PortalDto() {
    }
}
