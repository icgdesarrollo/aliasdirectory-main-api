package com.icg.aliasdirectory.mainapi.registration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.icg.aliasdirectory.mainapi.integrity.IntegritySeal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Escribe el padrón. Es la contraparte de {@code RegistryRepository}, que sólo lee.
 *
 * <p>Están separados a propósito. La lectura no toca el KMS ni descifra nada, y
 * ésa es una propiedad que conviene poder afirmar mirando una clase entera en vez
 * de auditando método por método. La escritura sí recibe material cifrado —ya
 * cifrado: aquí llegan bytes, no textos— y el KMS tampoco aparece en esta clase.
 * El cifrado ocurre una capa más arriba, en {@code RegistrationService}.
 *
 * <p>Ningún método de aquí abre transacción. La abre el servicio, porque un
 * registro son seis inserciones que valen todas o ninguna: un {@code holder} sin
 * su {@code alias_registration} es una fila que nadie va a encontrar nunca y que
 * ocupa el DPI para siempre por la unicidad de {@code dpi_bidx}.
 */
@Repository
public class RegistryWriteRepository {

    /**
     * Idioma de MySQL para «devuélveme el id, insertando sólo si hace falta».
     *
     * <p>El {@code UPDATE id = LAST_INSERT_ID(id)} parece no hacer nada y es lo
     * importante: cuando la fila ya existe, deja {@code LAST_INSERT_ID()} con el
     * id de la fila vieja, así que una sola ida al motor sirve para los dos casos
     * y no hay ventana entre el SELECT y el INSERT por la que se cuele otro hilo.
     *
     * <p>Lo que NO hace, también a propósito: no reescribe {@code dpi_enc}. El
     * cifrado es aleatorio, así que reescribirlo produciría bytes distintos para
     * el mismo DPI en cada registro del mismo titular, sin ganar nada y perdiendo
     * la posibilidad de notar que algo cambió cuando no debía.
     *
     * <p>Efecto colateral conocido: el AUTO_INCREMENT avanza aunque no se inserte.
     * Deja huecos en los ids, que no significan nada porque son sustitutos.
     */
    private static final String UPSERT_HOLDER = """
            INSERT INTO holder (schema_id, dpi_enc, dpi_bidx, dpi_bidx_ver)
            VALUES ('NIDN', :dpiEnc, :dpiBidx, :ver)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """;

    private static final String UPSERT_CUSTOMER_BANK = """
            INSERT INTO holder_bank (holder_id, bank_id, customer_id_enc,
                                     customer_id_bidx, customer_id_bidx_ver)
            VALUES (:holderId, :bankId, :idClienteEnc, :idClienteBidx, :ver)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """;

    /**
     * El tipo va como parámetro y no fijo en 'SHID' porque la columna es parte de
     * la clave única junto al índice ciego: si algún día entra otro tipo de alias,
     * el mismo número podría existir dos veces con tipos distintos y esta consulta
     * no debe ser la que lo impida por accidente.
     */
    private static final String UPSERT_ALIAS = """
            INSERT INTO alias (type_cd, value_enc, value_bidx, value_bidx_ver)
            VALUES (:tipo, :valorEnc, :valorBidx, :ver)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """;

    private static final String UPSERT_ACCOUNT = """
            INSERT INTO account (bank_id, iban_enc, iban_bidx, iban_bidx_ver,
                                 acct_type_enc, currency_enc)
            VALUES (:bankId, :ibanEnc, :ibanBidx, :ver, :tipoEnc, :monedaEnc)
            ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """;

    /**
     * El vínculo vigente del alias, si lo hay, con su titular.
     *
     * <p>Se consulta {@code current_alias_id}, la columna generada, y no
     * {@code released_at IS NULL}: son equivalentes, pero la generada es la que
     * tiene el índice único que garantiza que haya como mucho uno. Preguntar por
     * la misma columna que impone la regla evita que la consulta y la restricción
     * se desincronicen si alguien cambia una de las dos.
     */
    private static final String ACTIVE_LINK = """
            SELECT id, holder_id
              FROM alias_link
             WHERE current_alias_id = :aliasId
            """;

    /**
     * ¿Este cliente ya tiene un alias vigente en este banco? (regla V1.1.5)
     *
     * <p>Es la más estricta de las dos: un cliente registra un alias y ninguno
     * más en esa entidad, aunque sea contra otra cuenta suya. Fuera del alcance
     * de la v1.0.
     *
     * <p>No se entra por {@code holder_bank_id}, que es lo que el único usa,
     * porque esa fila puede no existir todavía cuando hay que preguntar: el
     * upsert ocurre después.
     *
     * <p>El OR no es por comodidad. {@code holder_bank} tiene DOS únicos —
     * {@code uk_customer_bank (bank_id, customer_id_bidx)} y
     * {@code uk_holder_per_bank (bank_id, holder_id)}— y el upsert resuelve a una
     * fila existente por cualquiera de los dos. Preguntando sólo por el IdCliente,
     * un alta con el mismo DPI y OTRO IdCliente no encontraría nada aquí, pasaría
     * la validación y reventaría al insertar: el {@code active_holder_bank_id} ya
     * estaría ocupado por la fila a la que el upsert la mandó. Eso es un 500 donde
     * corresponde un 409.
     */
    private static final String CUSTOMER_ACTIVE_REGISTRATION = """
            SELECT r.regn_id
              FROM alias_registration r
              JOIN holder_bank hb ON hb.id = r.active_holder_bank_id
              JOIN holder       h  ON h.id  = hb.holder_id
             WHERE hb.bank_id = :bankId
               AND (hb.customer_id_bidx = :customerIdBidx OR h.dpi_bidx = :dpiBidx)
             LIMIT 1
            """;

    /**
     * ¿Esta cuenta ya sostiene un alias vigente? (regla V1.1.4)
     *
     * <p>Se entra por {@code (bank_id, iban_bidx)} y no por {@code account_id}
     * porque en el momento de preguntar la cuenta puede no existir todavía: el
     * upsert ocurre después. Crearla antes sólo para poder preguntar dejaría una
     * cuenta sembrada por un alta que se va a rechazar.
     *
     * <p>Se consulta {@code active_account_id}, la columna generada, por la misma
     * razón que {@link #ACTIVE_LINK} consulta {@code current_alias_id}: es la que
     * tiene el índice único que impone la regla, así que la consulta y la
     * restricción no pueden desincronizarse.
     *
     * <p>Devuelve el {@code regn_id} y no un booleano para poder decir en el log
     * cuál registro está ocupando la cuenta. El RegnId es un identificador de
     * trámite del propio banco, no un dato del padrón: no lo alcanza la regla T-8.
     */
    private static final String ACCOUNT_ACTIVE_REGISTRATION = """
            SELECT r.regn_id
              FROM alias_registration r
              JOIN account a ON a.id = r.active_account_id
             WHERE a.bank_id   = :bankId
               AND a.iban_bidx = :ibanBidx
             LIMIT 1
            """;

    private static final String INSERT_LINK = """
            INSERT INTO alias_link (alias_id, holder_id)
            VALUES (:aliasId, :holderId)
            """;

    /**
     * El registro propiamente dicho.
     *
     * <p>{@code status} se deja en su valor por omisión ('ACTIVO') en vez de
     * escribirlo: un alta siempre nace activa, y si algún día eso cambia, el
     * cambio debe ser una migración visible y no una constante perdida en un SQL.
     */
    private static final String INSERT_REGISTRATION = """
            INSERT INTO alias_registration
                   (alias_uuid, regn_id, alias_id, alias_link_id, holder_id,
                    holder_bank_id, account_id, bank_id, nm_dsply_lvl,
                    registered_at, origin_msg_id)
            VALUES (UUID_TO_BIN(:uuid, 1), REPLACE(:uuid, '-', ''), :aliasId, :linkId,
                    :holderId, :clienteBancoId, :accountId, :bankId, :nivelNombre,
                    :registeredAt, :msgIdOrigen)
            """;

    /**
     * Le pone al registro su RegnId definitivo, derivado de su propio id.
     *
     * <p>Va en un UPDATE aparte porque el correlativo no existe hasta que el
     * motor asigna el AUTO_INCREMENT, y no se puede leer antes de insertar sin
     * inventar una secuencia propia —otra tabla, otro punto de contención, otra
     * cosa que se desincroniza—. Dentro de la misma transacción nadie llega a ver
     * el valor provisional.
     *
     * <p>El formato sale del Anexo: ICG-ALIAS- y doce dígitos. Con doce dígitos
     * alcanza para mil veces el padrón proyectado de cinco millones.
     */
    private static final String SET_REGN_ID = """
            UPDATE alias_registration
               SET regn_id = CONCAT('ICG-ALIAS-', LPAD(id, 12, '0'))
             WHERE id = :id
            """;

    /**
     * Le pone el sello de integridad al registro recien creado (H-57).
     *
     * <p>Va despues del INSERT por el mismo motivo que el RegnId: el sello cubre
     * el regn_id, que no existe hasta que el motor asigna el AUTO_INCREMENT.
     * Dentro de la misma transaccion nadie ve la fila sin sello.
     */
    private static final String SELLAR = """
            UPDATE alias_registration
               SET row_hmac = :sello, row_hmac_ver = :version
             WHERE id = :id
            """;

    /**
     * Los campos que entran al sello, para un alias dado.
     *
     * <p>Cruza cuatro tablas porque el sello cubre las cuatro: el registro dice a
     * que cuenta apunta, pero es account quien guarda el IBAN, y sellar solo el
     * account_id dejaria que cambiar el IBAN de esa cuenta pasara inadvertido.
     *
     * <p>Devuelve tambien row_hmac para poder comparar sin una segunda consulta.
     */
    private static final String SEALED_FIELDS = """
            SELECT BIN_TO_UUID(r.alias_uuid, 1) AS alias_uuid,
                   r.regn_id,
                   r.bank_id,
                   r.status,
                   r.nm_dsply_lvl,
                   a.value_bidx,
                   h.dpi_bidx,
                   c.iban_enc,
                   r.row_hmac
              FROM alias_registration r
              JOIN alias   a ON a.id = r.alias_id
              JOIN holder  h ON h.id = r.holder_id
              JOIN account c ON c.id = r.account_id AND c.bank_id = r.bank_id
             WHERE a.type_cd = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
            """;

    private static final String BANK_ID = """
            SELECT id FROM participant_bank WHERE bicfi = :bic
            """;

    private final JdbcClient jdbc;

    public RegistryWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return el id interno del banco, o vacío si el BIC no está en el padrón */
    public Optional<Integer> bankId(String bic) {
        return jdbc.sql(BANK_ID).param("bic", bic)
                .query(Integer.class).optional();
    }

    public long upsertHolder(byte[] dpiEnc, byte[] dpiBidx, int version) {
        return executeAndReturnId(UPSERT_HOLDER, m -> m
                .param("dpiEnc", dpiEnc)
                .param("dpiBidx", dpiBidx)
                .param("ver", version));
    }

    public long upsertCustomerBank(long holderId, int bankId, byte[] idClienteEnc,
            byte[] idClienteBidx, int version) {
        return executeAndReturnId(UPSERT_CUSTOMER_BANK, m -> m
                .param("holderId", holderId)
                .param("bankId", bankId)
                .param("idClienteEnc", idClienteEnc)
                .param("idClienteBidx", idClienteBidx)
                .param("ver", version));
    }

    public long upsertAlias(String tipo, byte[] valorEnc, byte[] valorBidx, int version) {
        return executeAndReturnId(UPSERT_ALIAS, m -> m
                .param("tipo", tipo)
                .param("valorEnc", valorEnc)
                .param("valorBidx", valorBidx)
                .param("ver", version));
    }

    public long upsertAccount(int bankId, byte[] ibanEnc, byte[] ibanBidx, int version,
            byte[] tipoEnc, byte[] monedaEnc) {
        return executeAndReturnId(UPSERT_ACCOUNT, m -> m
                .param("bankId", bankId)
                .param("ibanEnc", ibanEnc)
                .param("ibanBidx", ibanBidx)
                .param("ver", version)
                .param("tipoEnc", tipoEnc)
                .param("monedaEnc", monedaEnc));
    }

    /** El vínculo vigente del alias, si existe. */
    public Optional<Link> activeLink(long aliasId) {
        return jdbc.sql(ACTIVE_LINK).param("aliasId", aliasId)
                .query((rs, fila) -> new Link(rs.getLong("id"), rs.getLong("holder_id")))
                .optional();
    }

    /** El RegnId del alias vigente de este cliente en este banco, si lo tiene. */
    public Optional<String> customerActiveRegistration(int bankId, byte[] customerIdBidx,
            byte[] dpiBidx) {
        return jdbc.sql(CUSTOMER_ACTIVE_REGISTRATION)
                .param("bankId", bankId)
                .param("customerIdBidx", customerIdBidx)
                .param("dpiBidx", dpiBidx)
                .query(String.class).optional();
    }

    /** El RegnId del registro que ocupa la cuenta, si alguno la ocupa. */
    public Optional<String> accountActiveRegistration(int bankId, byte[] ibanBidx) {
        return jdbc.sql(ACCOUNT_ACTIVE_REGISTRATION)
                .param("bankId", bankId)
                .param("ibanBidx", ibanBidx)
                .query(String.class).optional();
    }

    public long insertLink(long aliasId, long holderId) {
        return executeAndReturnId(INSERT_LINK, m -> m
                .param("aliasId", aliasId)
                .param("holderId", holderId));
    }

    /**
     * Inserta el registro con un RegnId provisional: el UUID sin guiones.
     *
     * <p>La columna es única y NOT NULL, así que hace falta un valor desde el
     * primer momento. Se usa el UUID —único por construcción y de 32 caracteres,
     * dentro de los 35 de la columna— y no una constante, que chocaría con el
     * segundo registro de la transacción siguiente. Lo reemplaza
     * {@link #setRegnId} acto seguido.
     */
    public long insertRegistration(String uuid, long aliasId, long linkId,
            long holderId, long clienteBancoId, long accountId, int bankId,
            String nivelNombre, Instant registeredAt, String msgIdOrigen) {
        return executeAndReturnId(INSERT_REGISTRATION, m -> m
                .param("uuid", uuid)
                .param("aliasId", aliasId)
                .param("linkId", linkId)
                .param("holderId", holderId)
                .param("clienteBancoId", clienteBancoId)
                .param("accountId", accountId)
                .param("bankId", bankId)
                .param("nivelNombre", nivelNombre)
                .param("registeredAt", java.sql.Timestamp.from(registeredAt))
                .param("msgIdOrigen", msgIdOrigen));
    }

    /**
     * Fija el RegnId definitivo y lo devuelve.
     *
     * @return el RegnId tal como quedó en la fila, leído del motor y no
     *         reconstruido en Java: si el formato del SQL cambia, lo que se le
     *         responde al banco y lo que guarda la tabla siguen siendo el mismo
     *         valor, que es lo que el banco usará para reclamar después.
     */
    public String setRegnId(long registrationId) {
        int filas = jdbc.sql(SET_REGN_ID).param("id", registrationId).update();
        if (filas != 1) {
            throw new IllegalStateException(
                    "No se pudo fijar el RegnId del registro " + registrationId);
        }
        return jdbc.sql("SELECT regn_id FROM alias_registration WHERE id = :id")
                .param("id", registrationId)
                .query(String.class).single();
    }

    /**
     * El iban_enc tal como quedó en la cuenta.
     *
     * <p>Se lee de la base en vez de reusar los bytes que se cifraron un momento
     * antes, porque cuando la cuenta ya existía el upsert NO la reescribió: los
     * bytes guardados son los de su alta original, y el sello tiene que cubrir lo
     * que hay en la fila, no lo que se calculó en memoria.
     */
    public byte[] accountIban(long accountId, int bankId) {
        return jdbc.sql("SELECT iban_enc FROM account WHERE id = :id AND bank_id = :banco")
                .param("id", accountId)
                .param("banco", bankId)
                .query(byte[].class).single();
    }

    /** Guarda el sello del registro. */
    public void seal(long registrationId, byte[] seal, int version) {
        int filas = jdbc.sql(SELLAR)
                .param("sello", seal)
                .param("version", version)
                .param("id", registrationId)
                .update();
        if (filas != 1) {
            throw new IllegalStateException(
                    "No se pudo sellar el registro " + registrationId);
        }
    }

    /** Los campos sellados de los registros vigentes de un alias, con su sello. */
    public List<SealedRow> sealedFields(String tipo, byte[] aliasBidx) {
        return jdbc.sql(SEALED_FIELDS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query((rs, fila) -> new SealedRow(
                        new IntegritySeal.SealedFields(
                                rs.getString("alias_uuid"),
                                rs.getString("regn_id"),
                                rs.getInt("bank_id"),
                                rs.getString("status"),
                                rs.getString("nm_dsply_lvl"),
                                rs.getBytes("value_bidx"),
                                rs.getBytes("dpi_bidx"),
                                rs.getBytes("iban_enc")),
                        rs.getBytes("row_hmac")))
                .list();
    }

    /** Un registro con los campos que entran al sello y el sello que tiene guardado. */
    public record SealedRow(IntegritySeal.SealedFields fields, byte[] seal) {
    }

    /** Vínculo vigente entre un alias y el titular que lo tiene tomado. */
    public record Link(long id, long holderId) {
    }

    private long executeAndReturnId(String sql,
            java.util.function.UnaryOperator<JdbcClient.StatementSpec> parametros) {
        var keys = new GeneratedKeyHolder();
        parametros.apply(jdbc.sql(sql)).update(keys);
        Number id = keys.getKey();
        if (id == null) {
            // Con ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id) el driver siempre
            // devuelve una clave, así que llegar aquí significa que el SQL cambió y
            // dejó de ser un upsert. Vale la pena que se note ahora y no con una FK
            // apuntando a cero tres pasos más adelante.
            throw new IllegalStateException(
                    "El motor no devolvió id generado. ¿Se modificó el upsert? SQL: " + sql);
        }
        return id.longValue();
    }
}
