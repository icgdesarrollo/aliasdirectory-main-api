package com.icg.aliasdirectory.mainapi.registration;

import com.icg.aliasdirectory.mainapi.IntegrationMySql;
import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;
import com.icg.aliasdirectory.mainapi.availability.BlindIndex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El alta de un alias, de punta a punta, contra MySQL y OpenBao reales.
 *
 * <p>No hereda de {@code MySqlBackedTest} porque ésta necesita el KMS <em>encendido</em>:
 * el registro cifra, y con {@code icg.kms.enabled=false} sus beans no existen.
 * Es la única prueba del módulo que levanta el contexto con el KMS de verdad.
 *
 * <p>Lo que se comprueba no es «el método devuelve algo»: es que lo que quedó en
 * la base esté cifrado, que se pueda volver a leer, y que el alias registrado deje
 * de estar disponible. Un alta que escribe filas ilegibles pasaría cualquier
 * prueba que sólo mirara la respuesta XML.
 */
@Tag("integration")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistrationIntegrationTest {

    private static final String BANCO = "GTCOGTGC";
    private static final String OTRO_BANCO = "INDLGTGC";

    @Autowired
    private RegistrationService servicio;

    @Autowired
    private BlindIndex index;

    @org.junit.jupiter.api.BeforeAll
    void grantApplicationPermissions() {
        // Lo hace MySqlBackedTest para las demás; ésta no hereda de ella porque
        // necesita el KMS encendido, así que le toca hacerlo por su cuenta.
        IntegrationMySql.applyApplicationPermissions();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registration) {
        registration.add("spring.datasource.url", IntegrationMySql.CONTAINER::getJdbcUrl);
        registration.add("spring.datasource.username", () -> IntegrationMySql.APP_USER);
        registration.add("spring.datasource.password", () -> IntegrationMySql.APP_PASSWORD);
        registration.add("spring.flyway.user", () -> IntegrationMySql.MIGRATOR_USER);
        registration.add("spring.flyway.password", () -> IntegrationMySql.MIGRATOR_PASSWORD);

        // El KMS, encendido. Es lo que distingue a esta prueba.
        registration.add("icg.kms.enabled", () -> "true");
        registration.add("icg.kms.url", IntegrationOpenBao::url);
        registration.add("icg.kms.token", () -> IntegrationOpenBao.TOKEN);
        registration.add("icg.kms.encryption-key", () -> IntegrationOpenBao.ENCRYPTION_KEY);
        registration.add("icg.kms.index-key", () -> IntegrationOpenBao.INDEX_KEY);

        registration.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registration.add("spring.rabbitmq.ssl.enabled", () -> "false");
        registration.add("management.health.rabbit.enabled", () -> "false");
    }

    @Test
    @DisplayName("un alta deja el padrón consistente y todo lo sensible cifrado")
    void altaCompleta() throws SQLException {
        String alias = "+50244441111";
        String dpi = "1345986654379";
        var response = servicio.handle(
                request(BANCO, "PRXREQ-ALTA-1", alias, dpi, "CLI-0001",
                        "GT18BAGU01010000000000111111"), BANCO);

        assertThat(response.httpStatus())
                .as("un alta aceptada crea un recurso")
                .isEqualTo(201);
        String xml = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("PrxyRegnRpt").contains("ACTV");
        // El RegnId tiene el formato del Anexo y sale del correlativo de la tabla.
        assertThat(xml).containsPattern("ICG-ALIAS-\\d{12}");

        // Todas las consultas van filtradas POR ESTE alias, nunca contra la tabla
        // entera. La base es un contenedor compartido por todas las pruebas del
        // módulo y JUnit no garantiza el orden: contar filas totales hacía que
        // esta prueba dependiera de quién corrió antes, que es como se fabrica
        // una prueba intermitente.
        byte[] huella = index.ofAlias(alias);

        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD)) {

            // 1. Hay exactamente un registro activo para este alias.
            assertThat(anInteger(c, """
                    SELECT COUNT(*)
                      FROM alias_registration r
                      JOIN alias a ON a.id = r.alias_id
                     WHERE a.value_bidx = ? AND r.status = 'ACTIVO'
                    """, huella)).isEqualTo(1);

            // 2. Lo guardado NO es el valor en claro. Es la comprobación que
            //    justifica todo el esquema: si esto falla, el padrón está en texto
            //    plano y nada de lo demás importa.
            byte[] storedAlias = someBytes(c,
                    "SELECT value_enc FROM alias WHERE value_bidx = ?", huella);
            assertThat(new String(storedAlias, StandardCharsets.US_ASCII))
                    .as("el alias tiene que estar cifrado, no en claro")
                    .doesNotContain(alias)
                    .startsWith("vault:v1:");

            byte[] storedDpi = someBytes(c, """
                    SELECT h.dpi_enc
                      FROM holder h
                      JOIN alias_registration r ON r.holder_id = h.id
                      JOIN alias a ON a.id = r.alias_id
                     WHERE a.value_bidx = ?
                    """, huella);
            assertThat(new String(storedDpi, StandardCharsets.US_ASCII))
                    .doesNotContain(dpi)
                    .startsWith("vault:v1:");

            // 3. La huella guardada es la que calcularía una consulta. Si no
            //    coincidiera, el alias quedaría inencontrable: se habría
            //    registrado algo que nadie puede volver a hallar.
            assertThat(someBytes(c,
                    "SELECT value_bidx FROM alias WHERE value_bidx = ?", huella))
                    .isEqualTo(huella);

            // 4. Un solo vínculo vigente para este alias: la regla «un alias, un DPI».
            assertThat(anInteger(c, """
                    SELECT COUNT(*)
                      FROM alias_link l
                      JOIN alias a ON a.id = l.alias_id
                     WHERE a.value_bidx = ? AND l.released_at IS NULL
                    """, huella)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("registrar dos veces el mismo alias en el mismo banco lo rechaza")
    void aliasYaRegistrado() {
        String alias = "+50244442222";
        String dpi = "1122334455667";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-2", alias, dpi, "CLI-0002",
                "GT37BAGU01010000000000222222"), BANCO);

        var second = servicio.handle(
                request(BANCO, "PRXREQ-ALTA-3", alias, dpi, "CLI-0002",
                        "GT37BAGU01010000000000222222"), BANCO);

        // 409 y no 201: no se creó nada. Un 2xx sobre un alta rechazada le dice al
        // banco que el alias es suyo, y hay integraciones que sólo miran el código.
        assertThat(second.httpStatus()).isEqualTo(409);
        String xml = new String(second.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("RJCT").contains("ACTIVE_SAME_BANK");
    }

    @Test
    @DisplayName("el mismo alias a nombre de otro DPI, desde otro banco, se rechaza")
    void aliasDeOtraPersona() {
        String alias = "+50244443333";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-4", alias, "9012345678901", "CLI-0003",
                "GT56BAGU01010000000000333333"), BANCO);

        var second = servicio.handle(
                request(OTRO_BANCO, "PRXREQ-ALTA-5", alias, "0123456789012", "CLI-0004",
                        "GT52INDL01010000000000333333"), OTRO_BANCO);

        assertThat(second.httpStatus()).isEqualTo(409);
        String xml = new String(second.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("RJCT").contains("ACTIVE_OTHER_BANK_DIFF_DPI");
        // El BIC en conflicto viaja en AddtlInf para que el banco sepa a quién
        // reclamar. Se comprueba ahí y no en cualquier parte del XML: el BIC
        // también aparece en la cabecera, y un contains suelto pasaría aunque
        // AddtlInf viniera vacío.
        assertThat(xml).contains("<AddtlInf>Registrado en " + BANCO + "</AddtlInf>");
        // Ni el alias ni el DPI del titular original salen de vuelta (regla T-8).
        assertThat(xml).doesNotContain("9012345678901").doesNotContain("0123456789012");
    }

    @Test
    @DisplayName("el mismo alias se registra en otro banco para el mismo DPI: multibanco")
    void multibanco() throws SQLException {
        // Es la razón de ser del directorio: F3 devuelve una LISTA de bancos para
        // un alias. Si esto no pasara, el alias quedaría atrapado en la primera
        // entidad que lo registrara.
        String alias = "+50244443344";
        String dpi = "2233445566778";
        servicio.handle(request(BANCO, "PRXREQ-MB-1", alias, dpi, "CLI-0010",
                "GT78BAGU01010000000000101010"), BANCO);

        var second = servicio.handle(request(OTRO_BANCO, "PRXREQ-MB-2", alias, dpi,
                "CLI-0011", "GT74INDL01010000000000101010"), OTRO_BANCO);

        assertThat(second.httpStatus())
                .as("el multibanco con el mismo DPI es un alta válida, no un conflicto")
                .isEqualTo(201);
        assertThat(new String(second.body(), StandardCharsets.UTF_8))
                .contains("ACTV").doesNotContain("RJCT");

        byte[] huella = index.ofAlias(alias);
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD)) {
            // Un solo alias y un solo vínculo: es el mismo número de la misma
            // persona. Lo que se duplica es el registro, uno por entidad.
            assertThat(anInteger(c, "SELECT COUNT(*) FROM alias WHERE value_bidx = ?", huella))
                    .as("el alias no se duplica").isEqualTo(1);
            assertThat(anInteger(c, """
                    SELECT COUNT(*) FROM alias_link l
                      JOIN alias a ON a.id = l.alias_id
                     WHERE a.value_bidx = ? AND l.released_at IS NULL
                    """, huella))
                    .as("un solo vínculo vigente: el alias es de una sola persona").isEqualTo(1);
            assertThat(anInteger(c, """
                    SELECT COUNT(*) FROM alias_registration r
                      JOIN alias a ON a.id = r.alias_id
                     WHERE a.value_bidx = ? AND r.status = 'ACTIVO'
                    """, huella))
                    .as("un registro por entidad").isEqualTo(2);
        }
    }

    @Test
    @DisplayName("un cliente no admite un segundo alias en el mismo banco, ni en otra cuenta")
    void clienteYaTieneAliasEnElBanco() throws SQLException {
        String segundoAlias = "+50244448888";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-10", "+50244449999", "6789012345678",
                "CLI-0008", "GT54BAGU01010000000000888888"), BANCO);

        // MISMO cliente, OTRA cuenta. La cuenta distinta es deliberada: con la
        // misma, el rechazo podría venir de la V1.1.4 y esta prueba no
        // distinguiría cuál de las dos reglas actuó.
        var second = servicio.handle(request(BANCO, "PRXREQ-ALTA-11", segundoAlias,
                "6789012345678", "CLI-0008", "GT30BAGU01010000000000889999"), BANCO);

        assertThat(second.httpStatus()).isEqualTo(409);
        String xml = new String(second.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("RJCT").contains("ACTIVE_SAME_ACCOUNT");

        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD)) {
            assertThat(anInteger(c, "SELECT COUNT(*) FROM alias WHERE value_bidx = ?",
                    index.ofAlias(segundoAlias)))
                    .as("un alta rechazada no crea el alias")
                    .isZero();
            // Tampoco la cuenta nueva, que no llegó a existir.
            assertThat(anInteger(c, "SELECT COUNT(*) FROM account WHERE iban_bidx = ?",
                    index.ofIban("GT30BAGU01010000000000889999")))
                    .as("un alta rechazada no crea la cuenta")
                    .isZero();
        }
    }

    @Test
    @DisplayName("una cuenta no admite un segundo alias mientras el primero esté vigente")
    void cuentaYaSostieneUnAlias() throws SQLException {
        String iban = "GT75BAGU01010000000000444444";
        String segundoAlias = "+50244445555";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-6", "+50244444444", "3456789012345",
                "CLI-0005", iban), BANCO);

        // OTRO cliente del mismo banco contra la MISMA cuenta: el caso de la
        // cuenta mancomunada. Tiene que ser otro cliente, porque con el mismo lo
        // frenaría la V1.1.5 y esta prueba dejaría de probar lo que dice probar.
        var second = servicio.handle(request(BANCO, "PRXREQ-ALTA-7", segundoAlias,
                "5678901234567", "CLI-0007", iban), BANCO);

        assertThat(second.httpStatus()).isEqualTo(409);
        String xml = new String(second.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("RJCT").contains("ACTIVE_SAME_ACCOUNT");
        // Sin AddtlInf: la cuenta es del banco que pregunta y no hay otra entidad
        // que nombrar. Mandar un BIC aquí sería decirle al banco algo que ya sabe.
        assertThat(xml).doesNotContain("AddtlInf");

        // El rechazo no deja nada sembrado. Se comprueba sobre el alias nuevo, que
        // no debería ni existir: si la comprobación se hiciera dentro de
        // register(), la fila de alias quedaría creada y sólo el rollback la
        // quitaría.
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD)) {
            assertThat(anInteger(c, "SELECT COUNT(*) FROM alias WHERE value_bidx = ?",
                    index.ofAlias(segundoAlias)))
                    .as("un alta rechazada no crea el alias")
                    .isZero();
        }
    }

    @Test
    @DisplayName("la cuenta vuelve a admitir alias cuando el anterior queda INACTIVO")
    void laCuentaSeLiberaAlQuedarInactivo() throws SQLException {
        String iban = "GT94BAGU01010000000000555555";
        String primero = "+50244446666";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-8", primero, "4567890123456",
                "CLI-0006", iban), BANCO);

        // La baja de verdad la hace F5; aquí basta con dejar el registro INACTIVO,
        // que es lo único que la regla mira. BLOQUEADO no valdría: un alias en
        // cuarentena todavía ocupa la cuenta.
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD);
                PreparedStatement p = prepare(c, """
                        UPDATE alias_registration r
                          JOIN alias a ON a.id = r.alias_id
                           SET r.status = 'INACTIVO'
                         WHERE a.value_bidx = ?
                        """, index.ofAlias(primero))) {
            assertThat(p.executeUpdate()).isEqualTo(1);
        }

        var second = servicio.handle(request(BANCO, "PRXREQ-ALTA-9", "+50244447777",
                "4567890123456", "CLI-0006", iban), BANCO);

        assertThat(second.httpStatus())
                .as("con el alias anterior INACTIVO se liberan la cuenta y el cliente")
                .isEqualTo(201);
    }

    @Test
    @DisplayName("dos respuestas a la misma solicitud llevan MsgId distintos")
    void elMsgIdDeLaRespuestaEsPropio() {
        // El MsgId identifica al MENSAJE, no a la conversación: para eso está
        // OrgnlAssgnmt. Derivarlo del MsgId de la solicitud —«PRXRPT-» + el
        // original— hacía que el alta y el rechazo de su reintento salieran con el
        // mismo identificador, y el banco que archive por MsgId pierde la primera,
        // que es justamente la que trae el RegnId.
        // Identificadores propios: con la regla V1.1.5 compartir el IdCliente, el
        // DPI o la cuenta con otra prueba la vuelve dependiente del orden, y JUnit
        // no lo garantiza.
        String alias = "+50244440000";
        byte[] request = request(BANCO, "PRXREQ-ALTA-12", alias, "7890123456789",
                "CLI-0009", "GT73BAGU01010000000000999999");

        String primera = msgId(servicio.handle(request, BANCO));
        String second = msgId(servicio.handle(request, BANCO));

        assertThat(primera).isNotEqualTo(second);
        // Y ninguno arrastra el MsgId de la solicitud dentro del suyo.
        assertThat(primera).doesNotContain("PRXREQ-ALTA-12");
    }

    /** El MsgId de Assgnmt, que es el primero que aparece en la respuesta. */
    private static String msgId(ResponseBuilder.Response response) {
        String xml = new String(response.body(), StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<MsgId>([^<]+)</MsgId>").matcher(xml);
        assertThat(m.find()).as("la respuesta no trae MsgId").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("un Ownr sin IdCliente se rechaza antes de tocar la base")
    void faltaElIdCliente() {
        String sinCliente = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyRegn>
                    <Assgnmt>
                      <MsgId>PRXREQ-SIN-CLIENTE</MsgId>
                      <CreDtTm>2026-09-22T10:00:00Z</CreDtTm>
                      <Assgnr><Agt><FinInstnId><BICFI>GTCOGTGC</BICFI></FinInstnId></Agt></Assgnr>
                      <Assgne><Agt><FinInstnId><BICFI>ICGSGTGC</BICFI></FinInstnId></Agt></Assgne>
                    </Assgnmt>
                    <Prxy><Tp><Cd>SHID</Cd></Tp><Id>+50244444444</Id></Prxy>
                    <Acct>
                      <Id><IBAN>GT73BAGU01010000000000999999</IBAN></Id>
                      <Tp><Cd>SVGS</Cd></Tp><Ccy>GTQ</Ccy>
                    </Acct>
                    <Ownr><Id><PrvtId>
                      <Othr><Id>1345986654379</Id><SchmeNm><Cd>NIDN</Cd></SchmeNm></Othr>
                    </PrvtId></Id></Ownr>
                  </PrxyRegn>
                </Document>
                """;
        assertThatThrownBy(() ->
                servicio.handle(sinCliente.getBytes(StandardCharsets.UTF_8), BANCO))
                .isInstanceOf(RegistrationExtractor.InvalidRegistrationException.class);
    }

    /** Un PrxyRegn con los datos dados. */
    private static byte[] request(String bic, String msgId, String alias, String dpi,
            String customerId, String iban) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyRegn>
                    <Assgnmt>
                      <MsgId>%s</MsgId>
                      <CreDtTm>2026-09-22T10:00:00Z</CreDtTm>
                      <Assgnr><Agt><FinInstnId><BICFI>%s</BICFI></FinInstnId></Agt></Assgnr>
                      <Assgne><Agt><FinInstnId><BICFI>ICGSGTGC</BICFI></FinInstnId></Agt></Assgne>
                    </Assgnmt>
                    <Prxy><Tp><Cd>SHID</Cd></Tp><Id>%s</Id></Prxy>
                    <Acct>
                      <Id><IBAN>%s</IBAN></Id>
                      <Tp><Cd>SVGS</Cd></Tp><Ccy>GTQ</Ccy>
                    </Acct>
                    <Ownr><Id><PrvtId>
                      <Othr><Id>%s</Id><SchmeNm><Cd>NIDN</Cd></SchmeNm></Othr>
                      <Othr><Id>%s</Id><SchmeNm><Prtry>CUSTOMER_ID</Prtry></SchmeNm></Othr>
                    </PrvtId></Id></Ownr>
                  </PrxyRegn>
                </Document>
                """).formatted(msgId, bic, alias, iban, dpi, customerId)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static int anInteger(Connection c, String sql, Object... parametros)
            throws SQLException {
        try (PreparedStatement p = prepare(c, sql, parametros);
                ResultSet r = p.executeQuery()) {
            r.next();
            return r.getInt(1);
        }
    }

    private static byte[] someBytes(Connection c, String sql, Object... parametros)
            throws SQLException {
        try (PreparedStatement p = prepare(c, sql, parametros);
                ResultSet r = p.executeQuery()) {
            assertThat(r.next()).as("la consulta no devolvió ninguna fila: %s", sql).isTrue();
            return r.getBytes(1);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... parametros)
            throws SQLException {
        PreparedStatement p = c.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            p.setObject(i + 1, parametros[i]);
        }
        return p;
    }
}
