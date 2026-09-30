package com.icg.aliasdirectory.mainapi.kms;

// Jackson 3, no 2. Spring Boot 4 trae tools.jackson a traves de
// spring-boot-starter-jackson, que entra con starter-webmvc; el viejo
// com.fasterxml.jackson ya no esta en el classpath.
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cliente del motor Transit de OpenBao (E03-D03, E05-D06).
 *
 * <p>Es la única puerta por la que pasa material criptográfico en todo el
 * directorio. La aplicación no tiene llaves: manda valores y recibe resultados.
 * Lo que sí conoce son las RUTAS de las llaves, que son las mismas que guarda la
 * tabla {@code crypto_key}.
 *
 * <p><b>Todo va y viene en base64.</b> Transit exige el texto plano codificado y
 * devuelve el texto plano codificado; el ciphertext, en cambio, es una cadena
 * con formato propio ({@code vault:v1:…}) que se guarda tal cual en la columna.
 *
 * <p>Verificado contra OpenBao 2.6.2 real el 21/09/2026:
 * <ul>
 *   <li>el mismo valor cifrado dos veces da cadenas distintas;
 *   <li>las dos descifran al mismo texto;
 *   <li>el HMAC del mismo valor es idéntico entre llamadas;
 *   <li>ese HMAC, sin prefijo y decodificado, mide exactamente 32 bytes;
 *   <li>el ciphertext de un IBAN guatemalteco mide 85 caracteres, que entra
 *       holgado en {@code varbinary(256)}.
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
public class TransitClient {

    /**
     * {@code vault:v<N>:<base64>}. El número es la versión de la llave con que se
     * produjo el valor, y es lo que permite rotar sin reescribir nada: Transit
     * elige la llave correcta leyendo ese prefijo.
     */
    private static final Pattern FORMATO = Pattern.compile("^vault:v(\\d+):(.+)$");

    private final HttpClient http;
    // JsonMapper y no new ObjectMapper(): en Jackson 3 ese constructor quedó
    // obsoleto. El mapper es inmutable y seguro entre hilos, así que uno por
    // instancia alcanza.
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;
    private final String token;

    public TransitClient(@Value("${icg.kms.url}") String url,
            @Value("${icg.kms.token}") String token,
            @Value("${icg.kms.timeout-ms:2000}") long timeoutMs) {
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.token = token;
        this.http = HttpClient.newBuilder()
                // Corto a propósito, igual que el RPC de Dispatch: contra un SLA de
                // 100 ms, esperar el minuto que trae HttpClient por omisión no es
                // esperar, es dejar la petición colgada mucho después de que el
                // banco se rindió.
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    // ── Cifrado ──────────────────────────────────────────────────────────────

    /** @return el ciphertext tal como va a la columna, con su prefijo */
    public String encrypt(String key, String plaintext) {
        var body = json.createObjectNode();
        body.put("plaintext", toBase64(plaintext));
        return call("encrypt/" + key, body).get("ciphertext").asText();
    }

    /**
     * Descifra varios valores en UNA sola llamada.
     *
     * <p>No es una comodidad: medido en E19-D01 contra OpenBao real, los siete
     * campos de una resolución tardan 0.71 ms en lote y 4.84 ms uno por uno.
     * Descifrar en bucle es la forma más fácil de gastarse el presupuesto de
     * latencia sin notarlo.
     *
     * @return los textos planos, en el mismo orden en que entraron
     */
    public List<String> decrypt(String key, List<String> ciphertexts) {
        if (ciphertexts.isEmpty()) {
            return List.of();
        }
        ArrayNode lote = json.createArrayNode();
        for (String c : ciphertexts) {
            lote.add(json.createObjectNode().put("ciphertext", c));
        }
        var body = json.createObjectNode();
        body.set("batch_input", lote);

        JsonNode data = call("decrypt/" + key, body);
        JsonNode resultados = data.get("batch_results");
        var claros = new ArrayList<String>(ciphertexts.size());
        for (int i = 0; i < resultados.size(); i++) {
            JsonNode fila = resultados.get(i);
            // En lote, Transit responde 200 aunque UNA entrada falle: el error
            // viene dentro de su propia fila. Sin esta comprobación, un valor
            // corrupto se convertiría en un NullPointerException tres capas más
            // arriba, lejos de donde está el problema.
            if (fila.hasNonNull("error")) {
                throw new KmsException("El KMS no pudo descifrar el elemento " + i
                        + " del lote: " + fila.get("error").asText());
            }
            claros.add(fromBase64(fila.get("plaintext").asText()));
        }
        return claros;
    }

    /** Atajo para un solo valor. Prefiera el lote cuando haya más de uno. */
    public String decrypt(String key, String ciphertext) {
        return decrypt(key, List.of(ciphertext)).get(0);
    }

    // ── Índice ciego ─────────────────────────────────────────────────────────

    /**
     * HMAC-SHA256 calculado DENTRO del KMS: la llave nunca llega a este proceso.
     *
     * <p>Transit devuelve la huella con el mismo formato {@code vault:v1:…} que
     * el ciphertext, pero la columna {@code *_bidx} es {@code binary(32)}: hay
     * que separar el prefijo —que va a {@code *_bidx_ver}— y decodificar el
     * resto. Comprobado: son 32 bytes exactos.
     */
    public Fingerprint hmac(String key, String value) {
        return hmac(key, value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Lo mismo, sobre bytes.
     *
     * <p>Existe porque el sello de integridad (H-57) calcula el HMAC sobre una
     * cadena binaria armada con campos que ya son bytes —índices ciegos,
     * ciphertexts— y no sobre texto. Convertirla a String para volver a
     * convertirla a bytes aquí adentro funcionaría por casualidad y se rompería
     * el día que un byte no fuera UTF-8 válido.
     */
    public Fingerprint hmac(String key, byte[] value) {
        var body = json.createObjectNode();
        body.put("input", Base64.getEncoder().encodeToString(value));
        body.put("algorithm", "sha2-256");
        String crudo = call("hmac/" + key, body).get("hmac").asText();

        Matcher m = FORMATO.matcher(crudo);
        if (!m.matches()) {
            throw new KmsException("El KMS devolvió un HMAC con un formato inesperado: " + crudo);
        }
        byte[] bytes = Base64.getDecoder().decode(m.group(2));
        if (bytes.length != 32) {
            throw new KmsException("El índice ciego mide " + bytes.length
                    + " bytes; la columna declara 32");
        }
        return new Fingerprint(bytes, Integer.parseInt(m.group(1)));
    }

    /**
     * @param bytes   los 32 bytes que van a la columna {@code *_bidx}
     * @param version la versión de llave, que va a {@code *_bidx_ver}
     */
    public record Fingerprint(byte[] bytes, int version) {
    }

    // ── Fontanería ───────────────────────────────────────────────────────────

    private JsonNode call(String ruta, ObjectNode body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(base + "/v1/transit/" + ruta))
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(5000))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(),
                        StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode raiz = json.readTree(response.body());
            JsonNode data = raiz.get("data");

            // Transit responde 400 cuando ALGUNA entrada de un lote falla, aunque las
            // demas se hayan procesado bien, y pone el detalle por fila en
            // batch_results. Verificado contra OpenBao 2.6.2: un lote de dos con un
            // ciphertext invalido devuelve 400 con la primera fila resuelta y la
            // segunda con su error. Por eso ese caso NO se trata como fallo global:
            // se deja pasar para que quien llamo pueda decir cual elemento fallo.
            boolean loteConDetalle = data != null && data.has("batch_results");

            if (response.statusCode() != 200 && !loteConDetalle) {
                // El cuerpo del error trae el motivo del KMS («encryption key not
                // found», por ejemplo). Se propaga entero: un 400 sin explicacion
                // manda a revisar el codigo cuando el problema esta en el KMS.
                throw new KmsException("El KMS respondió " + response.statusCode()
                        + " a transit/" + ruta + ": " + response.body());
            }
            if (data == null) {
                throw new KmsException("El KMS respondió sin bloque «data» a transit/"
                        + ruta + ": " + response.body());
            }
            return data;
        } catch (IOException e) {
            throw new KmsException("No se pudo hablar con el KMS en " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KmsException("Interrumpido esperando al KMS", e);
        }
    }

    private static String toBase64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String fromBase64(String text) {
        return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
    }

    /** Falla del KMS. Nunca lleva el valor que se estaba cifrando (regla T-8). */
    public static class KmsException extends RuntimeException {
        public KmsException(String message) {
            super(message);
        }

        public KmsException(String message, Throwable causa) {
            super(message, causa);
        }
    }
}
