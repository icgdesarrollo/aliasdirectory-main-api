package com.icg.aliasdirectory.mainapi.uuidquery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.Optional;

/**
 * Lee un registro concreto por su AliasUUID (F7).
 *
 * <p>Entra por {@code uk_registration_uuid}, que es única: o hay una fila o no
 * hay ninguna. Por eso devuelve {@link Optional} y no una lista.
 *
 * <p><b>No filtra por estado</b>, a diferencia de F4. F7 pregunta por un
 * registro identificado, no por los alias operativos de alguien: quien consulta
 * un AliasUUID concreto —soporte, el portal, una investigación— necesita ver
 * también el que está bloqueado o dado de baja, porque justamente eso es lo que
 * está averiguando. El estado viaja en {@code Vrfctn}.
 */
@Repository
public class UuidQueryRepository {

    private static final String BY_UUID = """
            SELECT a.type_cd            AS alias_type,
                   a.value_enc          AS alias_enc,
                   c.iban_enc           AS iban_enc,
                   c.acct_type_enc      AS acct_type_enc,
                   c.currency_enc       AS currency_enc,
                   b.bicfi              AS bic,
                   r.status = 'ACTIVO'  AS active,
                   r.registered_at      AS registered_at,
                   r.nm_dsply_lvl       AS nm_dsply_lvl
              FROM alias_registration r
              JOIN alias            a ON a.id      = r.alias_id
              JOIN account          c ON c.id      = r.account_id
                                     AND c.bank_id = r.bank_id
              JOIN participant_bank b ON b.id      = r.bank_id
             WHERE r.alias_uuid = UUID_TO_BIN(:aliasUuid, 1)
            """;

    private final JdbcClient jdbc;

    public UuidQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<EncryptedRow> byUuid(String aliasUuid) {
        return jdbc.sql(BY_UUID)
                .param("aliasUuid", aliasUuid)
                .query((rs, row) -> new EncryptedRow(
                        rs.getString("alias_type"),
                        text(rs.getBytes("alias_enc")),
                        text(rs.getBytes("iban_enc")),
                        text(rs.getBytes("acct_type_enc")),
                        text(rs.getBytes("currency_enc")),
                        rs.getString("bic"),
                        rs.getBoolean("active"),
                        rs.getTimestamp("registered_at"),
                        rs.getString("nm_dsply_lvl")))
                .optional();
    }

    /** El registro con los cuatro campos todavía cifrados. */
    public record EncryptedRow(String aliasType, String aliasEnc, String ibanEnc,
            String accountTypeEnc, String currencyEnc, String bic, boolean active,
            Timestamp registeredAt, String nameDisplayLevel) {
    }

    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
