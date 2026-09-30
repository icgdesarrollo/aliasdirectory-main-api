package com.icg.aliasdirectory.mainapi.kms;

import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lo que el token de api-principal PUEDE y NO puede hacer contra el KMS (PTRAD-592).
 *
 * <p>Las demás pruebas del KMS usan el token raíz del modo dev, que puede todo.
 * Éstas usan el token acotado, que es el que corre en un despliegue real, y
 * cargan la política tal como está escrita en {@code docker/openbao/politicas/}:
 * si alguien afloja ese archivo, estas pruebas se ponen rojas.
 *
 * <p>El hallazgo que motiva esta subtarea: cifrar con un nombre de llave que no
 * existe NO fallaba. Transit creaba la llave al vuelo y devolvía un ciphertext
 * perfectamente válido, cifrado con una llave que no está en {@code crypto_key},
 * que nadie rota y que nadie respalda. Una letra de más en una propiedad bastaba.
 *
 * <p>Y lo que cierra ese agujero no es lo que uno supondría. Medido contra
 * OpenBao: lo decisivo es que la política NO conceda la capacidad {@code create}
 * sobre {@code transit/encrypt/&lt;llave&gt;}. Una política con {@code deny} sobre
 * {@code transit/keys/*} pero {@code create} en {@code encrypt} parece segura y
 * sigue creando llaves fantasma.
 */
@Tag("integration")
class KmsPolicyIntegrationTest {

    private static final String ALIAS = "+50244444444";

    private static String tokenApp;

    @BeforeAll
    static void issueToken() {
        tokenApp = IntegrationOpenBao.tokenWith("api-principal");
    }

    private static String b64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String cuerpoTexto(String value) {
        return "{\"plaintext\":\"" + b64(value) + "\"}";
    }

    // ------------------------------------------------------------ lo permitido ---

    @Test
    @DisplayName("el token acotado sigue sirviendo para lo que la aplicación hace")
    void theLegitimateCaseWorks() {
        // Una política que rompe el caso de uso no protege nada: la quitan el
        // primer día. Esta prueba existe para que el resto no se lea como que
        // "apretar más" siempre es mejor.
        TransitClient kms = new TransitClient(
                IntegrationOpenBao.url(), tokenApp, 2000);

        String cifrado = kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, ALIAS);
        assertThat(kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY, cifrado)).isEqualTo(ALIAS);
        assertThat(kms.hmac(IntegrationOpenBao.INDEX_KEY, ALIAS).bytes()).hasSize(32);
    }

    // --------------------------------------------------------- lo que se impide ---

    @Test
    @DisplayName("un nombre de llave mal escrito da 403 y no deja llave fantasma")
    void misspelledNameDoesNotCreateKey() {
        String malEscrita = "dir-alias-piii";
        assertThat(IntegrationOpenBao.keys()).doesNotContain(malEscrita);

        int code = IntegrationOpenBao.write(
                "/v1/transit/encrypt/" + malEscrita, cuerpoTexto(ALIAS), tokenApp);

        assertThat(code)
                .as("con el token raíz esto devolvía 200 y creaba la llave")
                .isEqualTo(403);
        // Lo que de verdad importa no es el código sino el efecto: que la llave
        // no exista después del intento. Se comprueba con el token raíz, porque
        // el acotado ni siquiera puede listar.
        assertThat(IntegrationOpenBao.keys()).doesNotContain(malEscrita);
    }

    @Test
    @DisplayName("el cliente traduce ese 403 en KmsException, no en un dato cifrado")
    void elClienteFallaFuerte() {
        // El recorrido completo tal como lo vería la aplicación: si esto devolviera
        // un ciphertext, el dato entraría a la tabla cifrado con una llave que no
        // existe en el inventario y nadie se enteraría hasta intentar leerlo.
        TransitClient kms = new TransitClient(
                IntegrationOpenBao.url(), tokenApp, 2000);

        assertThatThrownBy(() -> kms.encrypt("dir-alias-piii", ALIAS))
                .isInstanceOf(TransitClient.KmsException.class);
    }

    @Test
    @DisplayName("no puede crear llaves por la puerta de adelante")
    void cannotManageKeys() {
        int code = IntegrationOpenBao.write(
                "/v1/transit/keys/llave-pirata", "{\"type\":\"aes256-gcm96\"}", tokenApp);

        assertThat(code).isEqualTo(403);
        assertThat(IntegrationOpenBao.keys()).doesNotContain("llave-pirata");
    }

    @Test
    @DisplayName("no puede rotar una llave existente")
    void noPuedeRotar() {
        // La rotación tiene su propio procedimiento y su propia autorización
        // (PTRAD-593). Una aplicación que puede rotar puede dejar el padrón a
        // medio migrar sin que nadie lo haya decidido.
        int code = IntegrationOpenBao.write(
                "/v1/transit/keys/" + IntegrationOpenBao.ENCRYPTION_KEY + "/rotate",
                "{}", tokenApp);

        assertThat(code).isEqualTo(403);
    }

    @Test
    @DisplayName("la separación de llaves la sostiene la política, no el código")
    void lasLlavesNoSeCruzan() {
        // El diseño dice que quien calcula índices ciegos no debe poder descifrar.
        // Si eso dependiera sólo de qué constante pasa el código, un error de
        // programación lo rompería. Acá se comprueba que el KMS también lo impide.
        assertThat(IntegrationOpenBao.write(
                "/v1/transit/decrypt/" + IntegrationOpenBao.INDEX_KEY,
                "{\"ciphertext\":\"vault:v1:YWJjZA==\"}", tokenApp))
                .as("descifrar con la llave del índice ciego")
                .isEqualTo(403);

        assertThat(IntegrationOpenBao.write(
                "/v1/transit/hmac/" + IntegrationOpenBao.ENCRYPTION_KEY,
                "{\"input\":\"" + b64(ALIAS) + "\",\"algorithm\":\"sha2-256\"}", tokenApp))
                .as("calcular huella con la llave de cifrado")
                .isEqualTo(403);
    }

    // ------------------------------------------- la política misma, como texto ---

    @Test
    @DisplayName("la política del repositorio no concede «create» sobre encrypt")
    void laPoliticaNoConcedeCreate() throws IOException {
        // Esta prueba mira el archivo, no el comportamiento, y es a propósito:
        // las de arriba fallarían igual, pero con un mensaje que no dice QUÉ
        // cambiar. Ésta nombra la línea exacta.
        String hcl = Files.readString(IntegrationOpenBao.policyPath("api-principal"));

        String sinComentarios = hcl.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(sinComentarios)
                .as("«create» en un path de transit/encrypt deja que el KMS cree "
                    + "la llave al vuelo: es exactamente el defecto que esta "
                    + "subtarea corrige")
                .doesNotContain("\"create\"");

        assertThat(sinComentarios)
                .as("las llaves se nombran una por una; un comodín daría permiso "
                    + "sobre cualquier nombre, incluido uno mal escrito")
                .doesNotContain("transit/encrypt/*")
                .doesNotContain("transit/decrypt/*")
                .doesNotContain("transit/hmac/*");
    }
}
