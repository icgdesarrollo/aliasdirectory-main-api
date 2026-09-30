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

    public record AliasRegistrationView(String aliasUuid, String bic, String bankName, String iban,
            String accountType, String currency, String status, String registeredAt,
            boolean ownEntity) {
    }

    public record AliasLookup(String alias, String outcome, String eventId,
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

    private PortalDto() {
    }
}
