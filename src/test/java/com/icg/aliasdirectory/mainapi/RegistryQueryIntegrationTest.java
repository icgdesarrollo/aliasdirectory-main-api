package com.icg.aliasdirectory.mainapi;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.LocalHmacBlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Reason;
import com.icg.aliasdirectory.mainapi.availability.RegistryRepository;
import com.icg.aliasdirectory.mainapi.availability.AvailabilityRule;
import com.icg.aliasdirectory.mainapi.availability.ActiveRegistration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La consulta de disponibilidad, contra un MySQL de verdad.
 *
 * <p>Las pruebas del servicio usan un repositorio de mentira: prueban la regla,
 * no el SQL. Esta prueba existe porque la consulta —un JOIN de cuatro tablas que
 * compara BINARY(32) contra un byte[] enviado por JDBC— es exactamente el tipo de
 * código que compila, pasa las pruebas unitarias y falla la primera vez que toca
 * el motor.
 *
 * <p>El padrón se arma aquí con SQL directo y no con el servicio de registro,
 * que todavía no existe. Los campos cifrados llevan bytes cualesquiera: esta
 * consulta nunca descifra nada, y si algún día lo hiciera, la prueba fallaría,
 * que es justamente lo que se quiere.
 */
class RegistryQueryIntegrationTest extends MySqlBackedTest {

    private static final String YO = "GTCOGTGC";
    private static final String OTRO = "INDLGTGC";

    private static final String DPI_A = "1345986654379";
    private static final String DPI_B = "2987654321098";

    /** Un alias por escenario: el vínculo alias↔DPI es único mientras esté vigente. */
    private static final String ALIAS_MY_BANK = "+50244440001";
    private static final String ALIAS_OTHER_BANK = "+50244440002";
    private static final String ALIAS_QUARANTINED = "+50244440003";
    private static final String ALIAS_NOT_REGISTERED = "+50244440004";
    private static final String ALIAS_DEREGISTERED = "+50244440005";

    @Autowired
    private RegistryRepository registry;

    @Autowired
    private BlindIndex index;

    private static boolean montado;

    @BeforeAll
    void setUpRegistry() throws SQLException {
        if (montado) {
            return;
        }
        // Se monta como root: es fixture de prueba, no una operación de la
        // aplicación, y alias_directory_app a propósito no puede hacer todo esto.
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD)) {
            c.setAutoCommit(false);

            long bancoMio = bankId(c, YO);
            long bancoOtro = bankId(c, OTRO);

            BlindIndex idx = new LocalHmacBlindIndex(java.util.Base64.getEncoder().encodeToString(
                    "llave-de-prueba-de-32-bytes-1234".getBytes(StandardCharsets.UTF_8)), 1);

            long titularA = crearTitular(c, idx.ofDpi(DPI_A));
            long titularB = crearTitular(c, idx.ofDpi(DPI_B));

            long clienteMioA = crearCliente(c, titularA, bancoMio, "CLI-A-MIO");
            long clienteOtroA = crearCliente(c, titularA, bancoOtro, "CLI-A-OTRO");
            // El titular B existe pero no tiene alias: es quien pregunta por un
            // teléfono que resulta estar a nombre de A.
            crearCliente(c, titularB, bancoMio, "CLI-B-MIO");

            long cuentaMia = crearCuenta(c, bancoMio, "GT18BAGU01010000000000000001");
            long cuentaOtra = crearCuenta(c, bancoOtro, "GT18BAGU01010000000000000002");

            register(c, idx.ofAlias(ALIAS_MY_BANK), titularA, clienteMioA, cuentaMia,
                    bancoMio, "ACTIVO", 1);
            register(c, idx.ofAlias(ALIAS_OTHER_BANK), titularA, clienteOtroA, cuentaOtra,
                    bancoOtro, "ACTIVO", 2);
            register(c, idx.ofAlias(ALIAS_QUARANTINED), titularA, clienteMioA, cuentaMia,
                    bancoMio, "BLOQUEADO", 3);
            register(c, idx.ofAlias(ALIAS_DEREGISTERED), titularA, clienteMioA, cuentaMia,
                    bancoMio, "INACTIVO", 4);
            // ALIAS_NOT_REGISTERED no se inserta: es la fila «disponible».

            c.commit();
        }
        montado = true;
    }

    private AvailabilityRule.Resultado consultar(String alias, String dpi) {
        List<ActiveRegistration> registrations = registry.activeRegistrations(
                "SHID", index.ofAlias(alias), index.ofDpi(dpi));
        return AvailabilityRule.decide(registrations, YO);
    }

    @Test
    @DisplayName("alias sin registro: la consulta devuelve vacío y el alias está disponible")
    void disponible() {
        var r = consultar(ALIAS_NOT_REGISTERED, DPI_A);
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).isEmpty();
    }

    @Test
    @DisplayName("activo en mi banco con el mismo DPI: ACTIVE_SAME_BANK")
    void mismoBancoMismoDpi() {
        assertThat(consultar(ALIAS_MY_BANK, DPI_A).reason()).contains(Reason.ACTIVE_SAME_BANK);
    }

    @Test
    @DisplayName("activo en mi banco con otro DPI: ACTIVE_SAME_BANK_DIFF_DPI")
    void mismoBancoOtroDpi() {
        // Aquí se comprueba de verdad la comparación de índices ciegos en el
        // motor: el registro es del titular A y la consulta trae el DPI de B.
        assertThat(consultar(ALIAS_MY_BANK, DPI_B).reason())
                .contains(Reason.ACTIVE_SAME_BANK_DIFF_DPI);
    }

    @Test
    @DisplayName("activo en otra entidad con otro DPI: conflicto, con el BIC de esa entidad")
    void otroBancoOtroDpi() {
        var r = consultar(ALIAS_OTHER_BANK, DPI_B);
        assertThat(r.reason()).contains(Reason.ACTIVE_OTHER_BANK_DIFF_DPI);
        assertThat(r.reasonBic()).contains(OTRO);
    }

    @Test
    @DisplayName("activo en otra entidad con el mismo DPI: multibanco, sin motivo")
    void otroBancoMismoDpi() {
        var r = consultar(ALIAS_OTHER_BANK, DPI_A);
        assertThat(r.vrfctn()).isTrue();
        assertThat(r.reason()).isEmpty();
    }

    @Test
    @DisplayName("bloqueado: QUARANTINE, y Vrfctn=false porque no hay registro activo")
    void cuarentena() {
        var r = consultar(ALIAS_QUARANTINED, DPI_A);
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).contains(Reason.QUARANTINE);
    }

    @Test
    @DisplayName("un registro INACTIVO no ocupa el alias: vuelve a estar disponible")
    void inactivoNoCuenta() {
        // La consulta filtra por ACTIVO y BLOQUEADO. Que INACTIVO libere el cupo es
        // la regla del modelo —la columna generada active_bank_id— y lo que se
        // comprueba aquí es que la consulta la respeta.
        //
        // Va contra su propio alias y no mutando el de cuarentena: JUnit no
        // garantiza el orden de los métodos, y una prueba que cambia datos que otra
        // lee falla un día de cada tantos sin que nadie sepa por qué.
        var r = consultar(ALIAS_DEREGISTERED, DPI_A);
        assertThat(r.vrfctn()).isFalse();
        assertThat(r.reason()).isEmpty();
    }

    // ---- montaje -----------------------------------------------------------

    private static long bankId(Connection c, String bic) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM participant_bank WHERE bicfi = ?")) {
            ps.setString(1, bic);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next())
                        .as("el banco %s tiene que venir de la semilla de R__reference_catalogs", bic)
                        .isTrue();
                return rs.getLong(1);
            }
        }
    }

    private static long crearTitular(Connection c, byte[] dpiBidx) throws SQLException {
        return insert(c, """
                INSERT INTO holder (schema_id, dpi_enc, dpi_bidx) VALUES ('NIDN', ?, ?)
                """, ps -> {
            ps.setBytes(1, "cifrado".getBytes(StandardCharsets.UTF_8));
            ps.setBytes(2, dpiBidx);
        });
    }

    private static long crearCliente(Connection c, long titular, long banco, String customerId)
            throws SQLException {
        return insert(c, """
                INSERT INTO holder_bank (holder_id, bank_id, customer_id_enc, customer_id_bidx)
                VALUES (?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, titular);
            ps.setLong(2, banco);
            ps.setBytes(3, "cifrado".getBytes(StandardCharsets.UTF_8));
            ps.setBytes(4, padding(customerId));
        });
    }

    private static long crearCuenta(Connection c, long banco, String iban) throws SQLException {
        return insert(c, """
                INSERT INTO account (bank_id, iban_enc, iban_bidx, acct_type_enc, currency_enc)
                VALUES (?, ?, ?, ?, ?)
                """, ps -> {
            ps.setLong(1, banco);
            ps.setBytes(2, "cifrado".getBytes(StandardCharsets.UTF_8));
            ps.setBytes(3, padding(iban));
            ps.setBytes(4, "cifrado".getBytes(StandardCharsets.UTF_8));
            ps.setBytes(5, "cifrado".getBytes(StandardCharsets.UTF_8));
        });
    }

    private static void register(Connection c, byte[] aliasBidx, long titular, long cliente,
            long account, long banco, String status, int n) throws SQLException {
        long alias = insert(c, """
                INSERT INTO alias (type_cd, value_enc, value_bidx) VALUES ('SHID', ?, ?)
                """, ps -> {
            ps.setBytes(1, "cifrado".getBytes(StandardCharsets.UTF_8));
            ps.setBytes(2, aliasBidx);
        });

        long link = insert(c, """
                INSERT INTO alias_link (alias_id, holder_id) VALUES (?, ?)
                """, ps -> {
            ps.setLong(1, alias);
            ps.setLong(2, titular);
        });

        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO alias_registration
                  (alias_uuid, regn_id, alias_id, alias_link_id, holder_id, holder_bank_id,
                   account_id, bank_id, status, registered_at, quarantine_until)
                VALUES (UUID_TO_BIN(UUID(), 1), ?, ?, ?, ?, ?, ?, ?, ?, NOW(3), ?)
                """)) {
            ps.setString(1, "ICG-ALIAS-PRUEBA-" + n);
            ps.setLong(2, alias);
            ps.setLong(3, link);
            ps.setLong(4, titular);
            ps.setLong(5, cliente);
            ps.setLong(6, account);
            ps.setLong(7, banco);
            ps.setString(8, status);
            // La restricción ck_registration_quarantine exige fecha si está BLOQUEADO.
            if ("BLOQUEADO".equals(status)) {
                ps.setTimestamp(9, new java.sql.Timestamp(
                        System.currentTimeMillis() + 24L * 60 * 60 * 1000));
            } else {
                ps.setNull(9, java.sql.Types.TIMESTAMP);
            }
            ps.executeUpdate();
        }
    }

    /** Los índices ciegos son BINARY(32); para el montaje basta un relleno estable. */
    private static byte[] padding(String semilla) {
        byte[] b = new byte[32];
        byte[] s = semilla.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(s, 0, b, 0, Math.min(s.length, b.length));
        return b;
    }

    @FunctionalInterface
    private interface Parametros {
        void poner(PreparedStatement ps) throws SQLException;
    }

    private static long insert(Connection c, String sql, Parametros p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            p.poner(ps);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
