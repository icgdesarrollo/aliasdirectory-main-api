package com.icg.aliasdirectory.mainapi.customerquery;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Lee los alias vigentes de un cliente dentro de su propio banco (F4).
 *
 * <p>La búsqueda entra por {@code holder_bank}, que es la tabla que liga un
 * IdCliente a un banco. Eso hace que el aislamiento entre entidades no dependa
 * de acordarse de filtrar: el par (banco, IdCliente) YA es la clave única
 * {@code uk_customer_bank}, así que un banco no puede alcanzar el cliente de
 * otro ni equivocándose. Apoya en {@code ix_registration_customer_status}, que
 * el esquema creó para esta consulta.
 *
 * <p>Devuelve los campos cifrados tal como están en la base. Descifrar aquí
 * seria descifrar en SQL, que el Anexo §5.2 prohíbe; lo hace el servicio contra
 * el KMS, y en lote.
 */
@Repository
public class CustomerQueryRepository {

    /**
     * Sólo ACTIVO, a diferencia de las consultas de disponibilidad.
     *
     * <p>Allí se filtra por ACTIVO y BLOQUEADO porque lo que se pregunta es si
     * el cupo alias×banco está ocupado, y un alias en cuarentena lo ocupa. Aquí
     * la pregunta es otra —qué alias tiene operativos este cliente— y un alias
     * bloqueado no lo está. Listarlo haría que el banco se lo ofrezca a su
     * cliente y que la transferencia falle después.
     */
    private static final String ALIASES_OF_CUSTOMER = """
            SELECT BIN_TO_UUID(r.alias_uuid, 1) AS alias_uuid,
                   a.type_cd                    AS alias_type,
                   a.value_enc                  AS alias_enc,
                   c.iban_enc                   AS iban_enc,
                   c.acct_type_enc              AS acct_type_enc,
                   c.currency_enc               AS currency_enc,
                   b.bicfi                      AS bic
              FROM holder_bank        hb
              JOIN alias_registration r  ON r.holder_bank_id = hb.id
              JOIN alias              a  ON a.id             = r.alias_id
              JOIN account            c  ON c.id             = r.account_id
                                        AND c.bank_id        = r.bank_id
              JOIN participant_bank   b  ON b.id             = r.bank_id
             WHERE hb.bank_id          = :bankId
               AND hb.customer_id_bidx = :customerBidx
               AND r.status            = 'ACTIVO'
             ORDER BY r.registered_at
            """;

    private final JdbcClient jdbc;

    public CustomerQueryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<EncryptedRow> aliasesOfCustomer(int bankId, byte[] customerBidx) {
        return jdbc.sql(ALIASES_OF_CUSTOMER)
                .param("bankId", bankId)
                .param("customerBidx", customerBidx)
                .query((rs, row) -> new EncryptedRow(
                        rs.getString("alias_uuid"),
                        rs.getString("alias_type"),
                        text(rs.getBytes("alias_enc")),
                        text(rs.getBytes("iban_enc")),
                        text(rs.getBytes("acct_type_enc")),
                        text(rs.getBytes("currency_enc")),
                        rs.getString("bic")))
                .list();
    }

    /**
     * Una fila del padrón con los cuatro campos todavía cifrados.
     *
     * <p>El tipo es explícito para que no se pueda pasar por error un valor
     * cifrado donde se espera uno claro: los cuatro son cadenas
     * {@code vault:v1:…} y no significan nada hasta que el KMS las abra.
     */
    public record EncryptedRow(String aliasUuid, String aliasType, String aliasEnc,
            String ibanEnc, String accountTypeEnc, String currencyEnc, String bic) {
    }

    /**
     * El ciphertext de Transit es la cadena {@code vault:v1:…}, guardada en una
     * columna VARBINARY. Vuelve como bytes y hay que leerla como texto otra vez.
     */
    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
}
