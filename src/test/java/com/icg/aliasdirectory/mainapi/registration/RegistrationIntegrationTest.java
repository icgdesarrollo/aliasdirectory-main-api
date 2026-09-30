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
        String dpi = "2987654321098";
        servicio.handle(request(BANCO, "PRXREQ-ALTA-2", alias, dpi, "CLI-0002",
                "GT18BAGU01010000000000222222"), BANCO);

        var second = servicio.handle(
                request(BANCO, "PRXREQ-ALTA-3", alias, dpi, "CLI-0002",
                        "GT18BAGU01010000000000222222"), BANCO);

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
        servicio.handle(request(BANCO, "PRXREQ-ALTA-4", alias, "1345986654379", "CLI-0003",
                "GT18BAGU01010000000000333333"), BANCO);

        var second = servicio.handle(
                request(OTRO_BANCO, "PRXREQ-ALTA-5", alias, "2987654321098", "CLI-0004",
                        "GT18INDL01010000000000333333"), OTRO_BANCO);

        assertThat(second.httpStatus()).isEqualTo(409);
        String xml = new String(second.body(), StandardCharsets.UTF_8);
        assertThat(xml).contains("RJCT").contains("ACTIVE_OTHER_BANK_DIFF_DPI");
        // El BIC en conflicto viaja en AddtlInf para que el banco sepa a quién
        // reclamar. Se comprueba ahí y no en cualquier parte del XML: el BIC
        // también aparece en la cabecera, y un contains suelto pasaría aunque
        // AddtlInf viniera vacío.
        assertThat(xml).contains("<AddtlInf>Registrado en " + BANCO + "</AddtlInf>");
        // Ni el alias ni el DPI del titular original salen de vuelta (regla T-8).
        assertThat(xml).doesNotContain("1345986654379").doesNotContain("2987654321098");
    }

    @Test
    @DisplayName("dos respuestas a la misma solicitud llevan MsgId distintos")
    void elMsgIdDeLaRespuestaEsPropio() {
        // El MsgId identifica al MENSAJE, no a la conversación: para eso está
        // OrgnlAssgnmt. Derivarlo del MsgId de la solicitud —«PRXRPT-» + el
        // original— hacía que el alta y el rechazo de su reintento salieran con el
        // mismo identificador, y el banco que archive por MsgId pierde la primera,
        // que es justamente la que trae el RegnId.
        String alias = "+50244445555";
        byte[] request = request(BANCO, "PRXREQ-ALTA-6", alias, "1345986654379",
                "CLI-0006", "GT18BAGU01010000000000666666");

        String primera = msgId(servicio.handle(request, BANCO));
        String second = msgId(servicio.handle(request, BANCO));

        assertThat(primera).isNotEqualTo(second);
        // Y ninguno arrastra el MsgId de la solicitud dentro del suyo.
        assertThat(primera).doesNotContain("PRXREQ-ALTA-6");
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
                      <Id><IBAN>GT18BAGU01010000000000999999</IBAN></Id>
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
