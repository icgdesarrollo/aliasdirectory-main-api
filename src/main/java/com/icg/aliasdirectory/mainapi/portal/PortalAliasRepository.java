package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;

/**
 * Los registros de un alias en todas las entidades, para el portal.
 *
 * <p>Se parece a {@link com.icg.aliasdirectory.mainapi.resolution.ResolutionRepository}
 * y no lo reutiliza: aquel devuelve lo que un banco necesita para transferir, y
 * éste trae además el id del registro, el banco propietario, el estado y la
 * fecha, que son lo que un agente necesita para atender una llamada y lo que la
 * solicitud de baja referencia. Fundir ambos obligaría a que la ruta caliente de
 * F3 —con SLA de 100 ms— cargue columnas que no usa.
 *
 * <p>Trae ACTIVO y BLOQUEADO por la misma razón que F3: sin filas el alias no
 * existe, con todas bloqueadas existe y está en cuarentena, y filtrando por
 * ACTIVO las dos situaciones se verían iguales.
 */
@Repository
public class PortalAliasRepository {

    private static final String REGISTRATIONS_OF_ALIAS = """
            SELECT r.id                           AS registration_id,
                   BIN_TO_UUID(r.alias_uuid, 1)   AS alias_uuid,
                   r.bank_id                      AS bank_id,
                   b.bicfi                        AS bic,
                   b.entity_name                  AS bank_name,
                   c.iban_enc                     AS iban_enc,
                   c.acct_type_enc                AS acct_type_enc,
                   c.currency_enc                 AS currency_enc,
                   r.status                       AS status,
                   r.registered_at                AS registered_at
              FROM alias              a
              JOIN alias_registration r ON r.alias_id = a.id
              JOIN account            c ON c.id       = r.account_id
                                       AND c.bank_id  = r.bank_id
              JOIN participant_bank   b ON b.id       = r.bank_id
             WHERE a.type_cd    = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
             ORDER BY r.registered_at
            """;

    private final JdbcClient jdbc;

    public PortalAliasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EncryptedRow> registrationsOfAlias(String tipo, byte[] aliasBidx) {
        return jdbc.sql(REGISTRATIONS_OF_ALIAS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query((rs, row) -> new EncryptedRow(
                        rs.getLong("registration_id"),
                        rs.getString("alias_uuid"),
                        rs.getInt("bank_id"),
                        rs.getString("bic"),
                        rs.getString("bank_name"),
                        text(rs.getBytes("iban_enc")),
                        text(rs.getBytes("acct_type_enc")),
                        text(rs.getBytes("currency_enc")),
                        rs.getString("status"),
                        rs.getTimestamp("registered_at")))
                .list();
    }

    public record EncryptedRow(long registrationId, String aliasUuid, int bankId, String bic,
            String bankName, String ibanEnc, String accountTypeEnc, String currencyEnc,
            String status, Timestamp registeredAt) {
    }

    /** El ciphertext de Transit es {@code vault:v1:…} guardado en VARBINARY. */
    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
