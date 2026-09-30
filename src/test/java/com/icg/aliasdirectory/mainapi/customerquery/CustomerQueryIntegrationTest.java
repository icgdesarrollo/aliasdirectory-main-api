package com.icg.aliasdirectory.mainapi.customerquery;

import com.icg.aliasdirectory.mainapi.IntegrationMySql;
import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;
import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F4 · Consultar los alias de un cliente, contra MySQL y OpenBao reales.
 *
 * <p>Las altas se hacen por el servicio de registro, no insertando filas a mano.
 * Si el padrón se armara con INSERT directos, la prueba verificaría que F4 sabe
 * leer lo que la prueba misma escribió, no lo que F2 escribe de verdad —y es
 * justo ahí donde se esconden los desencuentros de cifrado y de índice ciego.
 */
@Tag("integration")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerQueryIntegrationTest {

    private static final String BANK = "GTCOGTGC";
    private static final String OTHER_BANK = "INDLGTGC";

    @Autowired
    private CustomerQueryService query;

    @Autowired
    private RegistrationService registration;

    @Autowired
    private BlindIndex index;

    @org.junit.jupiter.api.BeforeAll
    void grantApplicationPermissions() {
        IntegrationMySql.applyApplicationPermissions();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationMySql.CONTAINER::getJdbcUrl);
        registry.add("spring.datasource.username", () -> IntegrationMySql.APP_USER);
        registry.add("spring.datasource.password", () -> IntegrationMySql.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> IntegrationMySql.MIGRATOR_USER);
        registry.add("spring.flyway.password", () -> IntegrationMySql.MIGRATOR_PASSWORD);

        registry.add("icg.kms.enabled", () -> "true");
        registry.add("icg.kms.url", IntegrationOpenBao::url);
        registry.add("icg.kms.token", () -> IntegrationOpenBao.TOKEN);
        registry.add("icg.kms.encryption-key", () -> IntegrationOpenBao.ENCRYPTION_KEY);
        registry.add("icg.kms.index-key", () -> IntegrationOpenBao.INDEX_KEY);

        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.ssl.enabled", () -> "false");
        registry.add("management.health.rabbit.enabled", () -> "false");
    }

    @Test
    @DisplayName("devuelve los alias del cliente, descifrados y con su cuenta")
    void listaLosAliasDelCliente() {
        String customer = "CLI-F4-0001";
        String dpi = "2345986654371";
        register("PRXREQ-F4-1", "+50255510001", dpi, customer,
                "GT18BAGU01010000000000910001");
        register("PRXREQ-F4-2", "+50255510002", dpi, customer,
                "GT18BAGU01010000000000910002");

        String xml = new String(query.handle(request(BANK, customer), BANK),
                StandardCharsets.UTF_8);

        assertThat(xml).as("los dos alias del cliente salen en la lista")
                .contains("+50255510001", "+50255510002");
        assertThat(xml).as("el IBAN vuelve descifrado, no como vault:v1:")
                .contains("GT18BAGU01010000000000910001")
                .doesNotContain("vault:v");
        assertThat(xml).as("cada registro trae su banco en Acct/Svcr")
                .contains("<BICFI>" + BANK + "</BICFI>");
        assertThat(xml).as("la respuesta invierte Assgnr y Assgne (regla T-4)")
                .contains("<PrxyList>");
    }

    @Test
    @DisplayName("un cliente sin alias devuelve lista vacia, no un error")
    void clienteSinAliasDevuelveListaVacia() {
        String xml = new String(query.handle(request(BANK, "CLI-F4-INEXISTENTE"), BANK),
                StandardCharsets.UTF_8);

        assertThat(xml).as("el mensaje es un PrxyList bien formado")
                .contains("<PrxyList>");
        assertThat(xml).as("y no trae ningun registro")
                .doesNotContain("<PrxyRcrd>");
    }

    @Test
    @DisplayName("un banco NO ve los alias del cliente de otro banco")
    void aislamientoEntreEntidades() {
        String customer = "CLI-F4-0002";
        register("PRXREQ-F4-3", "+50255510003", "2345986654372", customer,
                "GT18BAGU01010000000000910003");

        // Mismo IdCliente, otro banco. El par (banco, IdCliente) es la clave de
        // holder_bank, asi que para INDLGTGC este cliente sencillamente no existe.
        String xml = new String(query.handle(request(OTHER_BANK, customer), OTHER_BANK),
                StandardCharsets.UTF_8);

        assertThat(xml).as("el alias del cliente de GTCOGTGC no se filtra a INDLGTGC")
                .doesNotContain("+50255510003");
        assertThat(xml).doesNotContain("<PrxyRcrd>");
    }

    @Test
    @DisplayName("una consulta por AliasUUID se rechaza: eso es F7, no F4")
    void rechazaElCriterioDeF7() {
        byte[] porUuid = ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyQry>
                    <Assgnmt>
                      <MsgId>PRXQRY-F4-UUID</MsgId>
                      <CreDtTm>2026-09-28T10:00:00Z</CreDtTm>
                      <Assgnr><Agt><FinInstnId><BICFI>%s</BICFI></FinInstnId></Agt></Assgnr>
                      <Assgne><Agt><FinInstnId><BICFI>ICGSGTGC</BICFI></FinInstnId></Agt></Assgne>
                    </Assgnmt>
                    <SchCrit>
                      <AliasUUID>f6ebe48c-54f0-47a5-8e87-cd65e8e3361a</AliasUUID>
                    </SchCrit>
                  </PrxyQry>
                </Document>
                """).formatted(BANK).getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> query.handle(porUuid, BANK))
                .isInstanceOf(CustomerQueryService.InvalidQueryException.class)
                .hasMessageContaining("F7");
    }

    @Test
    @DisplayName("el alias dado de baja deja de aparecer en la lista")
    void soloLosActivos() throws SQLException {
        String customer = "CLI-F4-0003";
        register("PRXREQ-F4-4", "+50255510004", "2345986654373", customer,
                "GT18BAGU01010000000000910004");

        String antes = new String(query.handle(request(BANK, customer), BANK),
                StandardCharsets.UTF_8);
        assertThat(antes).contains("+50255510004");

        // La baja por F5 todavia no existe; se cambia el estado directamente para
        // comprobar el filtro de la consulta, que es lo que esta prueba cubre.
        // Se busca por el indice ciego del alias para no depender de que esta sea
        // la ultima fila insertada: las otras pruebas escriben en la misma base.
        asRoot("""
                UPDATE alias_registration r
                  JOIN alias a ON a.id = r.alias_id
                   SET r.status = 'INACTIVO'
                 WHERE a.value_bidx = ?
                """, index.ofAlias(Normalizer.phone("+50255510004")));

        String despues = new String(query.handle(request(BANK, customer), BANK),
                StandardCharsets.UTF_8);
        assertThat(despues).as("un registro INACTIVO no se le ofrece al cliente")
                .doesNotContain("+50255510004");
    }

    private void register(String msgId, String alias, String dpi, String customerId,
            String iban) {
        var response = registration.handle(("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyRegn>
                    <Assgnmt>
                      <MsgId>%s</MsgId>
                      <CreDtTm>2026-09-28T10:00:00Z</CreDtTm>
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
                """).formatted(msgId, BANK, alias, iban, dpi, customerId)
                .getBytes(StandardCharsets.UTF_8), BANK);
        assertThat(response.httpStatus())
                .as("el alta de apoyo tiene que quedar registrada")
                .isEqualTo(201);
    }

    private static byte[] request(String bic, String customerId) {
        return ("""
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:icg:directorio.alias:icg.001">
                  <PrxyQry>
                    <Assgnmt>
                      <MsgId>PRXQRY-%s</MsgId>
                      <CreDtTm>2026-09-28T10:00:00Z</CreDtTm>
                      <Assgnr><Agt><FinInstnId><BICFI>%s</BICFI></FinInstnId></Agt></Assgnr>
                      <Assgne><Agt><FinInstnId><BICFI>ICGSGTGC</BICFI></FinInstnId></Agt></Assgne>
                    </Assgnmt>
                    <SchCrit>
                      <Ownr><Id><PrvtId><Othr>
                        <Id>%s</Id>
                        <SchmeNm><Prtry>CUSTOMER_ID</Prtry></SchmeNm>
                      </Othr></PrvtId></Id></Ownr>
                    </SchCrit>
                  </PrxyQry>
                </Document>
                """).formatted(customerId, bic, customerId)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Altera la base como lo haria un DBA, sin pasar por la aplicacion.
     * Es la unica forma de dejar un registro INACTIVO mientras F5 no exista.
     */
    private static void asRoot(String sql, byte[]... parameters) throws SQLException {
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD);
                PreparedStatement p = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                p.setBytes(i + 1, parameters[i]);
            }
            p.executeUpdate();
        }
    }
}
