package com.icg.aliasdirectory.mainapi.integrity;

import com.icg.aliasdirectory.mainapi.IntegrationMySql;
import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;
import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.registration.RegistrationService;

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
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H-57 de punta a punta: se altera la base como lo haría un DBA con {@code root}
 * y se comprueba que la verificación lo detecta.
 *
 * <p>Las alteraciones se hacen con una conexión directa como {@code root}, sin
 * pasar por la aplicación. Es exactamente el escenario del hallazgo: el cifrado
 * protege la confidencialidad, no la integridad, y un {@code UPDATE} no necesita
 * leer nada para reemplazar un IBAN por otro.
 */
@Tag("integration")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntegritySealIntegrationTest {

    private static final String BANCO = "GTCOGTGC";

    @Autowired
    private RegistrationService registration;

    @Autowired
    private RegistryVerifier verifier;

    @Autowired
    private BlindIndex index;

    @org.junit.jupiter.api.BeforeAll
    void permissions() {
        IntegrationMySql.applyApplicationPermissions();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", IntegrationMySql.CONTAINER::getJdbcUrl);
        r.add("spring.datasource.username", () -> IntegrationMySql.APP_USER);
        r.add("spring.datasource.password", () -> IntegrationMySql.APP_PASSWORD);
        r.add("spring.flyway.user", () -> IntegrationMySql.MIGRATOR_USER);
        r.add("spring.flyway.password", () -> IntegrationMySql.MIGRATOR_PASSWORD);
        r.add("icg.kms.enabled", () -> "true");
        r.add("icg.kms.url", IntegrationOpenBao::url);
        r.add("icg.kms.token", () -> IntegrationOpenBao.TOKEN);
        r.add("icg.kms.encryption-key", () -> IntegrationOpenBao.ENCRYPTION_KEY);
        r.add("icg.kms.index-key", () -> IntegrationOpenBao.INDEX_KEY);
        r.add("icg.kms.integrity-key", () -> IntegrationOpenBao.INTEGRITY_KEY);
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        r.add("spring.rabbitmq.ssl.enabled", () -> "false");
        r.add("management.health.rabbit.enabled", () -> "false");
    }

    @Test
    @DisplayName("un registro recién creado verifica bien")
    void recienCreadoVerifica() {
        String alias = "+50277770001";
        register(alias, "1345986654379", "CLI-7001", "GT18BAGU01010000000070001111");

        assertThatCode(() -> verifier.verify(RegistrationService.ALIAS_TYPE,
                index.ofAlias(alias))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el DBA cambia el IBAN de la cuenta y se detecta")
    void ibanCambiadoEnLaCuenta() throws SQLException {
        // ESTE es el escenario que motivó H-57: el DBA no toca alias_registration
        // —que es donde vive el sello— sino la tabla account. Si el sello cubriera
        // sólo account_id, el registro seguiría verificando bien y el alias
        // apuntaría a otra cuenta sin que nada lo notara.
        String alias = "+50277770002";
        register(alias, "2987654321098", "CLI-7002", "GT18BAGU01010000000070002222");
        byte[] huella = index.ofAlias(alias);

        asRoot("""
                UPDATE account c
                  JOIN alias_registration r ON r.account_id = c.id AND r.bank_id = c.bank_id
                  JOIN alias a ON a.id = r.alias_id
                   SET c.iban_enc = 'vault:v1:ELqueQuiereElDBA='
                 WHERE a.value_bidx = ?
                """, huella);

        assertThatThrownBy(() -> verifier.verify(RegistrationService.ALIAS_TYPE, huella))
                .isInstanceOf(RegistryVerifier.CompromisedIntegrityException.class);
    }

    @Test
    @DisplayName("el DBA reactiva un registro dado de baja y se detecta")
    void estadoCambiado() throws SQLException {
        String alias = "+50277770003";
        register(alias, "1345986654379", "CLI-7003", "GT18BAGU01010000000070003333");
        byte[] huella = index.ofAlias(alias);

        // El estado entra al sello porque decide si el registro responde. Sin él,
        // reactivar un alias dado de baja por fraude sería invisible.
        asRoot("""
                UPDATE alias_registration r
                  JOIN alias a ON a.id = r.alias_id
                   SET r.nm_dsply_lvl = 'FULL'
                 WHERE a.value_bidx = ?
                """, huella);

        assertThatThrownBy(() -> verifier.verify(RegistrationService.ALIAS_TYPE, huella))
                .isInstanceOf(RegistryVerifier.CompromisedIntegrityException.class);
    }

    @Test
    @DisplayName("copiar el sello de otro registro no sirve")
    void selloTrasplantado() throws SQLException {
        // El sello cubre alias_uuid y regn_id, que son distintos en cada fila. Sin
        // esa identidad dentro del HMAC, el DBA copiaría el sello de un registro
        // legítimo a la fila que acaba de alterar y ambos verificarían bien.
        String victima = "+50277770004";
        String propio = "+50277770005";
        register(victima, "1345986654379", "CLI-7004", "GT18BAGU01010000000070004444");
        register(propio, "2987654321098", "CLI-7005", "GT18BAGU01010000000070005555");

        byte[] huellaVictima = index.ofAlias(victima);
        asRoot("""
                UPDATE alias_registration destino
                  JOIN alias ad ON ad.id = destino.alias_id
                  JOIN (SELECT r.row_hmac AS sello
                          FROM alias_registration r
                          JOIN alias a ON a.id = r.alias_id
                         WHERE a.value_bidx = ?) origen
                   SET destino.row_hmac = origen.sello
                 WHERE ad.value_bidx = ?
                """, index.ofAlias(propio), huellaVictima);

        assertThatThrownBy(() -> verifier.verify(RegistrationService.ALIAS_TYPE, huellaVictima))
                .isInstanceOf(RegistryVerifier.CompromisedIntegrityException.class);
    }

    @Test
    @DisplayName("una fila sin sello se reporta, pero no se toma por alterada")
    void filaAnteriorAlControl() throws SQLException {
        // Las filas anteriores a H-57 no tienen sello y no se les puede calcular uno
        // honesto: nadie sabe qué les pasó antes de que existiera el control.
        // Tomarlas por alteradas dejaría sin servicio a todo el padrón existente;
        // tomarlas por íntegras sería afirmar algo que no consta. Se distinguen.
        String alias = "+50277770006";
        register(alias, "1345986654379", "CLI-7006", "GT18BAGU01010000000070006666");
        byte[] huella = index.ofAlias(alias);

        asRoot("""
                UPDATE alias_registration r
                  JOIN alias a ON a.id = r.alias_id
                   SET r.row_hmac = NULL, r.row_hmac_ver = NULL
                 WHERE a.value_bidx = ?
                """, huella);

        assertThatCode(() -> verifier.verify(RegistrationService.ALIAS_TYPE, huella))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el sello guardado mide 32 bytes y trae su versión de llave")
    void formaDelSello() throws SQLException {
        String alias = "+50277770007";
        register(alias, "2987654321098", "CLI-7007", "GT18BAGU01010000000070007777");

        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD);
                PreparedStatement p = c.prepareStatement("""
                        SELECT r.row_hmac, r.row_hmac_ver
                          FROM alias_registration r
                          JOIN alias a ON a.id = r.alias_id
                         WHERE a.value_bidx = ?
                        """)) {
            p.setBytes(1, index.ofAlias(alias));
            try (var rs = p.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getBytes("row_hmac"))
                        .as("la columna declara BINARY(32)")
                        .hasSize(32);
                assertThat(rs.getInt("row_hmac_ver")).isPositive();
            }
        }
    }

    private void register(String alias, String dpi, String customerId, String iban) {
        var response = registration.handle(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyRegn>
                    <Assgnmt>
                      <MsgId>PRXREQ-SELLO-%s</MsgId>
                      <CreDtTm>2026-09-23T10:00:00Z</CreDtTm>
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
                """).formatted(customerId, BANCO, alias, iban, dpi, customerId)
                .getBytes(StandardCharsets.UTF_8), BANCO);
        assertThat(response.httpStatus())
                .as("el montaje de la prueba necesita que el alta funcione")
                .isEqualTo(201);
    }

    /** Escribe en la base sin pasar por la aplicación, como haría un DBA. */
    private static void asRoot(String sql, byte[]... parametros) throws SQLException {
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD);
                PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                p.setBytes(i + 1, parametros[i]);
            }
            assertThat(p.executeUpdate())
                    .as("la alteración simulada no modificó ninguna fila: %s", sql)
                    .isPositive();
        }
    }
}
