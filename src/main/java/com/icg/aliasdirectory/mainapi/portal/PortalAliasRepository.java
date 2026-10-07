package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.List;

/**
 * Las cuatro consultas del portal (Anexo §8.1), sobre los índices ciegos.
 *
 * <p>Las cuatro devuelven la misma fila porque el portal muestra siempre los
 * mismos campos (Anexo §8.3): lo que cambia es por dónde se entra —alias,
 * cuenta, DPI o cliente—, no qué se ve.
 *
 * <p>Ninguna descifra: eso sería descifrar en SQL, que el Anexo §5.2 prohíbe.
 * Las columnas salen cifradas y el servicio las abre contra el KMS, en lote.
 *
 * <p>Se parece a {@link com.icg.aliasdirectory.mainapi.resolution.ResolutionRepository}
 * y no lo reutiliza: aquél devuelve lo que un banco necesita para transferir, y
 * éste trae además el banco propietario, el estado y la fecha. Fundirlos
 * obligaría a la ruta caliente de F3 —con SLA de 100 ms— a cargar columnas que
 * no usa.
 */
@Repository
public class PortalAliasRepository {

    /** Lo que el Anexo §8.3 manda mostrar, más lo que la baja necesita. */
    private static final String COLUMNS = """
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
            """;

    /**
     * Por alias, en todas las entidades.
     *
     * <p>Trae ACTIVO y BLOQUEADO: sin filas el alias no existe, con todas
     * bloqueadas existe y está en cuarentena, y filtrando por ACTIVO las dos
     * situaciones se verían iguales.
     */
    private static final String BY_ALIAS = COLUMNS + """
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

    /**
     * Por número de cuenta, SÓLO en la entidad del operador.
     *
     * <p>Una cuenta pertenece a un banco —{@code account} tiene {@code bank_id}
     * en su clave— así que buscar la cuenta de otra entidad no tiene sentido
     * operativo: el agente no puede hacer nada con ella y el número de cuenta de
     * un cliente ajeno es justo lo que el padrón protege. Es la única de las
     * cuatro consultas que se limita a la propia entidad por naturaleza del dato.
     */
    private static final String BY_ACCOUNT = COLUMNS + """
              FROM account            c
              JOIN alias_registration r ON r.account_id = c.id
                                       AND r.bank_id    = c.bank_id
              JOIN alias              a ON a.id         = r.alias_id
              JOIN participant_bank   b ON b.id         = r.bank_id
             WHERE c.iban_bidx = :ibanBidx
               AND c.bank_id   = :bankId
               AND r.status IN ('ACTIVO','BLOQUEADO')
             ORDER BY r.registered_at
            """;

    /**
     * Por DPI del titular, en todas las entidades.
     *
     * <p>Cross-banco como la de alias: el titular es la persona, no el registro,
     * y el agente que atiende una disputa necesita ver dónde más está. Apoya en
     * {@code ix_registration_holder_status}, que el esquema creó para esto.
     */
    private static final String BY_HOLDER = COLUMNS + """
              FROM holder             h
              JOIN alias_registration r ON r.holder_id = h.id
              JOIN alias              a ON a.id        = r.alias_id
              JOIN account            c ON c.id        = r.account_id
                                       AND c.bank_id   = r.bank_id
              JOIN participant_bank   b ON b.id        = r.bank_id
             WHERE h.dpi_bidx = :dpiBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
             ORDER BY r.registered_at
            """;

    /**
     * Por IdCliente, con el mismo criterio que F4.
     *
     * <p>Sólo ACTIVO y sólo de la entidad del operador, porque el IdCliente es
     * un identificador interno de cada banco: el mismo valor en otra entidad es
     * otra persona. El Anexo §8.1 lo describe como «listar sus alias activos».
     */
    private static final String BY_CUSTOMER = COLUMNS + """
              FROM holder_bank        hb
              JOIN alias_registration r ON r.holder_bank_id = hb.id
              JOIN alias              a ON a.id             = r.alias_id
              JOIN account            c ON c.id             = r.account_id
                                       AND c.bank_id        = r.bank_id
              JOIN participant_bank   b ON b.id             = r.bank_id
             WHERE hb.bank_id          = :bankId
               AND hb.customer_id_bidx = :customerBidx
               AND r.status            = 'ACTIVO'
             ORDER BY r.registered_at
            """;

    private final JdbcClient jdbc;

    public PortalAliasRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EncryptedRow> byAlias(String tipo, byte[] aliasBidx) {
        return jdbc.sql(BY_ALIAS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query(EncryptedRow.MAPPER)
                .list();
    }

    public List<EncryptedRow> byAccount(byte[] ibanBidx, int bankId) {
        return jdbc.sql(BY_ACCOUNT)
                .param("ibanBidx", ibanBidx)
                .param("bankId", bankId)
                .query(EncryptedRow.MAPPER)
                .list();
    }

    public List<EncryptedRow> byHolder(byte[] dpiBidx) {
        return jdbc.sql(BY_HOLDER)
                .param("dpiBidx", dpiBidx)
                .query(EncryptedRow.MAPPER)
                .list();
    }

    public List<EncryptedRow> byCustomer(byte[] customerBidx, int bankId) {
        return jdbc.sql(BY_CUSTOMER)
                .param("customerBidx", customerBidx)
                .param("bankId", bankId)
                .query(EncryptedRow.MAPPER)
                .list();
    }

    public record EncryptedRow(long registrationId, String aliasUuid, int bankId, String bic,
            String bankName, String ibanEnc, String accountTypeEnc, String currencyEnc,
            String status, Timestamp registeredAt) {

        static final org.springframework.jdbc.core.RowMapper<EncryptedRow> MAPPER =
                (rs, row) -> new EncryptedRow(
                        rs.getLong("registration_id"),
                        rs.getString("alias_uuid"),
                        rs.getInt("bank_id"),
                        rs.getString("bic"),
                        rs.getString("bank_name"),
                        text(rs.getBytes("iban_enc")),
                        text(rs.getBytes("acct_type_enc")),
                        text(rs.getBytes("currency_enc")),
                        rs.getString("status"),
                        rs.getTimestamp("registered_at"));
    }

    /** El ciphertext de Transit es {@code vault:v1:…} guardado en VARBINARY. */
    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
