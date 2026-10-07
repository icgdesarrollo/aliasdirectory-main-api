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
 * Las cuatro consultas del portal de Call Center (Anexo §8.1).
 *
 * <p>Alcance: por alias y por DPI se ven los registros de TODAS las entidades,
 * porque un cliente que llama pregunta por algo que puede estar en cualquier
 * banco y el Anexo §8.2 pide indicar cuál es para facilitar la coordinación. Por
 * cuenta y por cliente se ven sólo los de la entidad del operador, porque esos
 * dos identificadores son internos de cada banco. Dar de baja, en cambio, es
 * siempre de la propia entidad, y eso lo decide {@link DeactivationService}.
 *
 * <p><b>La cuenta sale enmascarada</b> (Anexo §8.3), y se enmascara aquí y no en
 * el navegador: un IBAN completo que sale de este servicio ya está en la máquina
 * del agente aunque la pantalla pinte asteriscos.
 *
 * <p>Descifra, así que necesita KMS y no existe sin él.
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

    // ---- por alias --------------------------------------------------------

    public PortalDto.AliasLookup byAlias(String rawAlias, PortalUser user) {
        String alias = Normalizer.phone(rawAlias);
        byte[] aliasBidx = index.ofAlias(alias);

        // H-57: los registros que se van a mostrar no pueden haber sido alterados
        // por fuera de la aplicación. Un agente que dicta datos por teléfono está
        // tomando la misma decisión que una transferencia.
        verifier.ifPresent(v -> v.verify(
                RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx));

        var rows = registry.byAlias(RegistrationAvailabilityService.ALIAS_TYPE, aliasBidx);
        return report("ALIAS", rows, user);
    }

    // ---- por cuenta -------------------------------------------------------

    public PortalDto.AliasLookup byAccount(String rawIban, PortalUser user) {
        if (!user.belongsToBank()) {
            throw PortalException.forbidden("Un usuario sin entidad no puede consultar por cuenta:"
                    + " el número de cuenta sólo identifica un registro dentro de su banco");
        }
        byte[] ibanBidx = index.ofIban(Normalizer.iban(rawIban));
        return report("CUENTA", registry.byAccount(ibanBidx, user.bankId()), user);
    }

    // ---- por DPI ----------------------------------------------------------

    public PortalDto.AliasLookup byHolder(String rawDpi, PortalUser user) {
        byte[] dpiBidx = index.ofDpi(Normalizer.dpi(rawDpi));
        return report("DPI", registry.byHolder(dpiBidx), user);
    }

    // ---- por cliente ------------------------------------------------------

    public PortalDto.AliasLookup byCustomer(String rawCustomerId, PortalUser user) {
        if (!user.belongsToBank()) {
            throw PortalException.forbidden("Un usuario sin entidad no puede consultar por cliente:"
                    + " el IdCliente es un identificador interno de cada banco");
        }
        byte[] customerBidx = index.ofCustomerId(Normalizer.customerId(rawCustomerId));
        return report("CLIENTE", registry.byCustomer(customerBidx, user.bankId()), user);
    }

    // ---- lo común ---------------------------------------------------------

    private PortalDto.AliasLookup report(String criterion,
            List<PortalAliasRepository.EncryptedRow> rows, PortalUser user) {

        String eventId = UUID.randomUUID().toString();

        if (rows.isEmpty()) {
            log.info("portal consulta: usuario={} criterio={} evento={} resultado=NOT_FOUND",
                    user.username(), criterion, eventId);
            return new PortalDto.AliasLookup(criterion, "NOT_FOUND", eventId, List.of());
        }

        boolean anyActive = rows.stream().anyMatch(r -> "ACTIVO".equals(r.status()));
        String outcome = anyActive ? "RESOLVED" : "BLOCKED";

        // El valor consultado, el IBAN y el IdCliente NUNCA salen al log (regla
        // T-8). Con el identificador del evento se rastrea lo demás en la
        // bitácora, que es donde esos datos sí pueden estar, enmascarados.
        log.info("portal consulta: usuario={} criterio={} evento={} resultado={} registros={}",
                user.username(), criterion, eventId, outcome, rows.size());

        return new PortalDto.AliasLookup(criterion, outcome, eventId, decrypt(rows, user));
    }

    /**
     * Descifra IBAN, tipo y moneda de todas las filas en UNA llamada al KMS, y
     * enmascara la cuenta antes de que salga de aquí.
     *
     * <p>Tres viajes por fila serían nueve para un alias en tres entidades. La
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
                    Mask.lastFour(plain.get(base)), plain.get(base + 1), plain.get(base + 2),
                    row.status(), DATE.format(row.registeredAt().toInstant()), ownEntity));
        }
        return views;
    }
}
