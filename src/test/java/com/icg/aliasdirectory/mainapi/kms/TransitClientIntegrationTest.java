package com.icg.aliasdirectory.mainapi.kms;

import com.icg.aliasdirectory.mainapi.IntegrationOpenBao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El cliente de Transit contra un OpenBao real.
 *
 * <p>No hay contexto de Spring a propósito: lo que se ejercita es el contrato
 * HTTP con el KMS, y levantar la aplicación entera para eso sólo añadiría
 * motivos de fallo ajenos a lo que se quiere comprobar.
 *
 * <p>Cada prueba fija una propiedad de la que depende el diseño del padrón, no
 * un detalle de implementación. Si alguna cae, hay una decisión del esquema que
 * deja de sostenerse.
 */
@Tag("integration")
class TransitClientIntegrationTest {

    private static final String ALIAS = "+50244444444";

    private static final String IBAN = "GT18BAGU01010000000000111111";

    private final TransitClient kms = new TransitClient(
            IntegrationOpenBao.url(), IntegrationOpenBao.TOKEN, 2000);

    @Test
    @DisplayName("lo cifrado vuelve igual")
    void idaYVuelta() {
        String cifrado = kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, IBAN);
        assertThat(cifrado).startsWith("vault:v1:");
        assertThat(kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY, cifrado)).isEqualTo(IBAN);
    }

    @Test
    @DisplayName("el mismo valor cifrado dos veces da cadenas distintas")
    void elCifradoEsAleatorio() {
        // Es la propiedad que obliga a que exista el índice ciego: si esto
        // fallara, la columna cifrada sería indexable y sobraría la otra.
        String a = kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, ALIAS);
        String b = kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, ALIAS);
        assertThat(a).isNotEqualTo(b);
        assertThat(kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY, a)).isEqualTo(ALIAS);
        assertThat(kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY, b)).isEqualTo(ALIAS);
    }

    @Test
    @DisplayName("el ciphertext de un IBAN entra en varbinary(256)")
    void cabeEnLaColumna() {
        // Medido: 85 caracteres. El margen es amplio, pero la columna es parte
        // del contrato y conviene que un cambio de formato del KMS se note acá y
        // no con un dato truncado en producción.
        assertThat(kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, IBAN).length())
                .isLessThanOrEqualTo(256);
    }

    @Test
    @DisplayName("el descifrado en lote respeta el orden de entrada")
    void loteEnOrden() {
        // Si el orden no se respetara, un IBAN acabaría asignado al alias
        // equivocado sin que nada falle: es el error más caro posible aquí.
        List<String> values = List.of("uno", "dos", "tres", "cuatro");
        List<String> cifrados = values.stream()
                .map(v -> kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, v))
                .toList();
        assertThat(kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY, cifrados))
                .containsExactlyElementsOf(values);
    }

    @Test
    @DisplayName("un elemento corrupto del lote falla diciendo cuál")
    void loteConUnElementoMalo() {
        // Transit responde 200 aunque una entrada del lote falle, con el error
        // dentro de su fila. Sin la comprobación del cliente, esto sería un
        // NullPointerException tres capas más arriba.
        String bueno = kms.encrypt(IntegrationOpenBao.ENCRYPTION_KEY, ALIAS);
        assertThatThrownBy(() -> kms.decrypt(IntegrationOpenBao.ENCRYPTION_KEY,
                List.of(bueno, "vault:v1:esto-no-es-un-ciphertext")))
                .isInstanceOf(TransitClient.KmsException.class)
                .hasMessageContaining("elemento 1");
    }

    @Test
    @DisplayName("el índice ciego mide 32 bytes y trae su versión de llave")
    void huellaDeTreintaYDosBytes() {
        TransitClient.Fingerprint h = kms.hmac(IntegrationOpenBao.INDEX_KEY, ALIAS);
        assertThat(h.bytes()).hasSize(32);
        assertThat(h.version()).isEqualTo(1);
    }

    @Test
    @DisplayName("el índice ciego es determinista: el mismo valor da la misma huella")
    void huellaDeterminista() {
        // Sin esto no se puede buscar por igualdad, que es la única razón por la
        // que la columna existe.
        assertThat(kms.hmac(IntegrationOpenBao.INDEX_KEY, ALIAS).bytes())
                .isEqualTo(kms.hmac(IntegrationOpenBao.INDEX_KEY, ALIAS).bytes());
    }

    @Test
    @DisplayName("valores distintos dan huellas distintas")
    void huellasSeparan() {
        assertThat(kms.hmac(IntegrationOpenBao.INDEX_KEY, ALIAS).bytes())
                .isNotEqualTo(kms.hmac(IntegrationOpenBao.INDEX_KEY, "+50255556666").bytes());
    }

    @Test
    @DisplayName("HALLAZGO: cifrar con una llave inexistente NO falla, Transit la crea")
    void cifrarConLlaveInexistenteLaCrea() {
        // Comprobado contra OpenBao 2.6.2. Esta prueba fija un comportamiento del KMS
        // que NO es el que uno esperaría, y que es un riesgo operativo real:
        //
        //   un nombre de llave mal escrito no produce ningún error. Transit crea una
        //   llave nueva al vuelo y cifra con ella. Los datos quedan cifrados con una
        //   llave que no está en crypto_key, que nadie respalda y que nadie rota; y
        //   el día que se descubra, lo cifrado con ella sólo se recupera si esa llave
        //   sigue viva en el KMS.
        //
        // La defensa NO está en este cliente sino en la POLÍTICA del token, y el
        // detalle que la hace funcionar no es el que parece. Medido contra OpenBao:
        // lo decisivo es que la política no conceda la capacidad «create» sobre
        // transit/encrypt/<llave>. Denegar transit/keys/* NO alcanza —una política
        // con deny ahí y create en encrypt sigue creando la llave al vuelo—.
        // Ver KmsPolicyIntegrationTest y docker/openbao/politicas/LEEME.md.
        //
        // Aquí se usa el token raíz del modo dev, que puede todo, así que la prueba
        // documenta el comportamiento del KMS en vez de fingir que no ocurre. Con el
        // token acotado este mismo caso da 403, y eso lo fija la otra prueba.
        String cifrado = kms.encrypt("llave-que-no-existia", ALIAS);
        assertThat(cifrado).startsWith("vault:v1:");
        assertThat(kms.decrypt("llave-que-no-existia", cifrado)).isEqualTo(ALIAS);
    }

    @Test
    @DisplayName("descifrar con una llave inexistente sí falla, con el motivo del KMS")
    void descifrarConLlaveInexistente() {
        // El otro lado sí protege: no hay nada que crear al vuelo para descifrar.
        assertThatThrownBy(() -> kms.decrypt("otra-llave-que-no-existe",
                "vault:v1:YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXo="))
                .isInstanceOf(TransitClient.KmsException.class);
    }
}
