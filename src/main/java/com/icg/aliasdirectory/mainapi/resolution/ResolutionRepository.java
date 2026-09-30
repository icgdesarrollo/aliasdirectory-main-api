package com.icg.aliasdirectory.mainapi.resolution;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lee los registros de un alias en TODAS las entidades, con la cuenta (F3).
 *
 * <p>Es la consulta que hace posible el multibanco: el mismo teléfono puede
 * estar registrado en varios bancos, y la resolución devuelve la lista completa
 * para que el banco consultante elija a cuál transferir. Apoya en
 * {@code ix_registration_alias_status}, que el esquema creó para esto.
 *
 * <p>Se traen ACTIVO y BLOQUEADO, no sólo ACTIVO. La diferencia decide el código
 * de respuesta: sin ninguna fila el alias no existe (404); con filas donde todas
 * están bloqueadas el alias existe y está en cuarentena (409 con
 * {@code Rsn.Prtry}). Filtrando por ACTIVO se perdería esa distinción y las dos
 * situaciones saldrían como un 404, que le diría al banco algo falso.
 *
 * <p>Devuelve los campos cifrados tal como están. Descifrar aquí sería descifrar
 * en SQL, que el Anexo §5.2 prohíbe; lo hace el servicio contra el KMS, y en
 * lote.
 */
@Repository
public class ResolutionRepository {

    private static final String REGISTRATIONS_OF_ALIAS = """
            SELECT BIN_TO_UUID(r.alias_uuid, 1) AS alias_uuid,
                   a.type_cd                    AS alias_type,
                   a.value_enc                  AS alias_enc,
                   c.iban_enc                   AS iban_enc,
                   c.acct_type_enc              AS acct_type_enc,
                   c.currency_enc               AS currency_enc,
                   b.bicfi                      AS bic,
                   r.status = 'ACTIVO'          AS active
              FROM alias              a
              JOIN alias_registration r ON r.alias_id  = a.id
              JOIN account            c ON c.id        = r.account_id
                                       AND c.bank_id   = r.bank_id
              JOIN participant_bank   b ON b.id        = r.bank_id
             WHERE a.type_cd    = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
             ORDER BY r.registered_at
            """;

    private final JdbcClient jdbc;

    public ResolutionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EncryptedRow> registrationsOfAlias(String tipo, byte[] aliasBidx) {
        return jdbc.sql(REGISTRATIONS_OF_ALIAS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query((rs, row) -> new EncryptedRow(
                        rs.getString("alias_uuid"),
                        rs.getString("alias_type"),
                        text(rs.getBytes("alias_enc")),
                        text(rs.getBytes("iban_enc")),
                        text(rs.getBytes("acct_type_enc")),
                        text(rs.getBytes("currency_enc")),
                        rs.getString("bic"),
                        rs.getBoolean("active")))
                .list();
    }

    /**
     * Una fila del padrón con los cuatro campos todavía cifrados.
     *
     * @param active true si está ACTIVO; false si está BLOQUEADO (en cuarentena)
     */
    public record EncryptedRow(String aliasUuid, String aliasType, String aliasEnc,
            String ibanEnc, String accountTypeEnc, String currencyEnc, String bic,
            boolean active) {
    }

    /**
     * El ciphertext de Transit es la cadena {@code vault:v1:…}, guardada en una
     * columna VARBINARY. Vuelve como bytes y hay que leerla como texto otra vez.
     */
    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
