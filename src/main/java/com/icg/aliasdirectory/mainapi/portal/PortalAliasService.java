package com.icg.aliasdirectory.mainapi.portal;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
import com.icg.aliasdirectory.mainapi.availability.RegistrationAvailabilityService;
import com.icg.aliasdirectory.mainapi.integrity.RegistryVerifier;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Consulta de un alias desde el portal de Call Center.
 *
 * <p>Alcanza los registros de TODAS las entidades, no sólo los del banco del
 * agente: un cliente que llama a su banco pregunta por un alias que puede estar
 * en cualquier otro. Lo que sí queda limitado a su entidad es dar de baja, y eso
 * lo decide {@link DeactivationService}, no esta consulta.
 *
 * <p>Descifra, así que necesita KMS y no existe sin él, igual que la resolución
 * y el registro.
 */
@ConditionalOnProperty(name = {"icg.kms.enabled", "icg.portal.enabled"}, havingValue = "true")
@Service
public class PortalAliasService {

    private static final Logger log = LoggerFactory.getLogger(PortalAliasService.class);

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final BlindIndex index;
    private final PortalAliasRepository registry;
    private final TransitClient kms;
    private final Optional<RegistryVerifier> verifier;
    private final String encryptionKey;

    public PortalAliasService(BlindIndex index, PortalAliasRepository registry, TransitClient kms,
            Optional<RegistryVerifier> verifier,
            @Value("${icg.kms.encryption-key}") String encryptionKey) {
        this.index = index;
        this.registry = registry;
        this.kms = kms;
        this.verifier = verifier;
        this.encryptionKey = encryptionKey;
    }

    public PortalDto.AliasLookup lookup(String rawAlias, PortalUser user) {
        String alias = Normalizer.phone(rawAlias);
        byte[] aliasBidx = index.ofAlias(alias);

        // H-57: los registros que se van a mostrar no pueden haber sido
        // alterados por fuera de la aplicación. Un agente que dicta una cuenta
        // por teléfono está tomando la misma decisión que una transferencia.
        verifier.ifPresent(v -> v.verify(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx));

        var rows = registry.registrationsOfAlias(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx);
        String eventId = UUID.randomUUID().toString();

        if (rows.isEmpty()) {
            log.info("portal consulta alias: usuario={} evento={} resultado=NOT_FOUND",
                    user.username(), eventId);
            return new PortalDto.AliasLookup(rawAlias, "NOT_FOUND", eventId, List.of());
        }

        var views = decrypt(rows, user);
        boolean anyActive = rows.stream().anyMatch(r -> "ACTIVO".equals(r.status()));
        String outcome = anyActive ? "RESOLVED" : "BLOCKED";

        // El alias, el IBAN y el IdCliente NUNCA salen al log (regla T-8). Con el
        // identificador del evento se rastrea todo lo demás en la bitácora, que
        // es donde esos datos sí pueden estar y enmascarados.
        log.info("portal consulta alias: usuario={} evento={} resultado={} registros={}",
                user.username(), eventId, outcome, rows.size());

        return new PortalDto.AliasLookup(rawAlias, outcome, eventId, views);
    }

    /**
     * Descifra IBAN, tipo y moneda de todas las filas en UNA llamada al KMS.
     *
     * <p>Tres viajes por banco serían nueve para un alias en tres entidades. La
     * medición de E04-D02 da 0.99 ms por identificador en lote contra 4.84 ms
     * suelto.
     */
    private List<PortalDto.AliasRegistrationView> decrypt(
            List<PortalAliasRepository.EncryptedRow> rows, PortalUser user) {

        var ciphertexts = new ArrayList<String>(rows.size() * 3);
        for (var row : rows) {
            ciphertexts.add(row.ibanEnc());
            ciphertexts.add(row.accountTypeEnc());
            ciphertexts.add(row.currencyEnc());
        }
        List<String> plain = kms.decrypt(encryptionKey, ciphertexts);

        var views = new ArrayList<PortalDto.AliasRegistrationView>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            int base = i * 3;
            boolean ownEntity = user.belongsToBank() && user.bankId() == row.bankId();
            views.add(new PortalDto.AliasRegistrationView(
                    row.aliasUuid(), row.bic(), row.bankName(),
                    plain.get(base), plain.get(base + 1), plain.get(base + 2),
                    row.status(), DATE.format(row.registeredAt().toInstant()), ownEntity));
        }
        return views;
    }
}
