package com.icg.aliasdirectory.mainapi;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Un solo OpenBao para todas las pruebas del módulo (patrón singleton de Testcontainers).
 *
 * <p>Arranca en modo dev —memoria volátil, token fijo, sin sellar— que es
 * exactamente lo que necesita una prueba: cada corrida empieza con un KMS limpio
 * y las llaves se crean acá mismo, con las mismas rutas que usa
 * {@code docker/openbao/init-transit.sh}.
 *
 * <p>Es pública, al contrario de {@code IntegrationMySql}: sus pruebas viven en
 * otros paquetes ({@code kms}, {@code disponibilidad}) porque lo que ejercitan es
 * el cliente del KMS y el índice ciego, no la capa de persistencia.
 *
 * <p>Las llaves se crean por HTTP y no ejecutando el script del repositorio. Es
 * deliberado: el script usa el CLI {@code bao}, y lo que hay que ejercitar aquí
 * es la API que consume la aplicación. Si el día de mañana cambia el formato de
 * una respuesta, esta prueba lo ve y el script no.
 */
public final class IntegrationOpenBao {

    /** Misma versión que compose.yaml: el formato del ciphertext es contrato. */
    public static final String IMAGEN = "openbao/openbao:2.6.2";

    public static final String TOKEN = "token-de-pruebas";

    public static final String ENCRYPTION_KEY = "dir-alias-pii";

    public static final String INDEX_KEY = "dir-alias-bidx";

    /** Tercera llave, para el sello de integridad (H-57). */
    public static final String INTEGRITY_KEY = "dir-alias-integridad";

    public static final GenericContainer<?> CONTAINER = create();

    /** Mismo mapeador que usa TransitClient: Spring Boot 4 trae Jackson 3. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private IntegrationOpenBao() {
    }

    private static GenericContainer<?> create() {
        GenericContainer<?> container = new GenericContainer<>(IMAGEN)
                .withEnv("BAO_DEV_ROOT_TOKEN_ID", TOKEN)
                .withCommand("server", "-dev", "-dev-listen-address=0.0.0.0:8200")
                .withExposedPorts(8200)
                // El endpoint de salud responde 200 sólo cuando está inicializado y
                // sin sellar. Esperar al log sería más frágil: el texto cambia entre
                // versiones y la espera quedaría verde con el KMS aún arrancando.
                .waitingFor(Wait.forHttp("/v1/sys/health")
                        .forPort(8200)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)));
        container.start();

        // La URL se arma desde la variable LOCAL, no llamando a url(). Ese metodo lee
        // el campo CONTAINER, que durante este inicializador estatico todavia vale
        // null —la asignacion aun no ha terminado— y la preparacion reventaba con un
        // NullPointerException disfrazado de ExceptionInInitializerError.
        String url = "http://" + container.getHost() + ":" + container.getMappedPort(8200);

        post(url, "/v1/sys/mounts/transit", "{\"type\":\"transit\"}");
        post(url, "/v1/transit/keys/" + ENCRYPTION_KEY, "{\"type\":\"aes256-gcm96\"}");
        // El tipo es "hmac"; el algoritmo (sha2-256) se elige al invocar el
        // endpoint. No existe un tipo "hmac-sha256" —suponerlo costó un
        // «encryption key not found» en la primera puesta en marcha.
        post(url, "/v1/transit/keys/" + INDEX_KEY, "{\"type\":\"hmac\",\"key_size\":32}");
        // Separada de la del índice ciego a propósito: si el sello se calculara
        // con la misma llave, quien pudiera pedir índices ciegos podría fabricar
        // sellos válidos para una fila recién alterada.
        post(url, "/v1/transit/keys/" + INTEGRITY_KEY, "{\"type\":\"hmac\",\"key_size\":32}");
        return container;
    }

    /** URL que ve la aplicación, con el puerto que Testcontainers publicó. */
    public static String url() {
        return "http://" + CONTAINER.getHost() + ":" + CONTAINER.getMappedPort(8200);
    }

    private static void post(String url, String ruta, String body) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url + ruta))
                .header("X-Vault-Token", TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            // 200 y 204 son éxito. Se comprueba explícitamente en vez de ignorar el
            // código: un montaje que falla en silencio reaparece mucho después
            // como «encryption key not found», lejos de su causa.
            if (r.statusCode() != 200 && r.statusCode() != 204) {
                throw new IllegalStateException(
                        "No se pudo preparar el KMS de pruebas (" + ruta + "): "
                        + r.statusCode() + " " + r.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo preparar el KMS de pruebas: " + ruta, e);
        }
    }

    // ------------------------------------------------------------------ PTRAD-592
    // Politicas y tokens acotados. Hasta aqui todas las pruebas usaban el token
    // raiz del modo dev, que puede todo; lo que sigue permite ejercitar lo que un
    // servicio REALMENTE puede hacer, que es lo unico que protege en produccion.

    /**
     * Carga en el KMS la politica tal como esta escrita en el repositorio.
     *
     * <p>Lee el {@code .hcl} de verdad, no una copia dentro de la prueba. Es
     * deliberado: si alguien agrega la capacidad {@code create} al path de
     * {@code encrypt}, las pruebas de abajo se ponen rojas. Una copia en el test
     * seguiria verde mientras el archivo desplegado abre el agujero.
     */
    public static void loadPolicy(String nombre) {
        Path archivo = policyPath(nombre);
        String hcl;
        try {
            hcl = Files.readString(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer la politica " + archivo, e);
        }
        // El HCL viaja dentro de un JSON. Se arma con Jackson en vez de a mano:
        // escapar comillas, barras y saltos a mano funciona hasta que el archivo
        // llega con CRLF de Windows, y entonces el KMS responde 400 por un
        // caracter de control invalido, error que no se parece en nada a su causa.
        ObjectNode body = JSON.createObjectNode().put("policy", hcl);
        post(url(), "/v1/sys/policies/acl/" + nombre, body.toString());
    }

    /** Ruta del {@code .hcl} tal como se despliega, en el repositorio de infraestructura. */
    public static Path policyPath(String nombre) {
        return InfraRepository.file("docker/openbao/politicas/" + nombre + ".hcl",
                "Las pruebas de PTRAD-592 verifican el archivo que se despliega,"
                + " no una copia, y sin el no tienen nada que verificar.");
    }

    /**
     * Emite un token con esa unica politica, con TTL corto.
     *
     * <p>Sin {@code no_default_policy} el token lleva ademas la politica
     * {@code default}, que no concede nada sobre {@code transit/} pero si sobre
     * rutas propias del token. Se desactiva para que lo que se mida sea la
     * politica del servicio y nada mas.
     */
    public static String tokenWith(String politica) {
        loadPolicy(politica);
        ObjectNode body = JSON.createObjectNode();
        body.putArray("policies").add(politica);
        body.put("no_default_policy", true).put("ttl", "10m");
        JsonNode raiz = request("/v1/auth/token/create", body.toString(), TOKEN);
        return raiz.get("auth").get("client_token").asText();
    }

    /** Nombres de las llaves que existen ahora mismo, leidos con el token raiz. */
    public static List<String> keys() {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url() + "/v1/transit/keys?list=true"))
                .header("X-Vault-Token", TOKEN)
                .GET()
                .build();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("No se pudieron listar las llaves: "
                        + r.statusCode() + " " + r.body());
            }
            List<String> nombres = new ArrayList<>();
            JSON.readTree(r.body()).get("data").get("keys")
                    .forEach(n -> nombres.add(n.asText()));
            return nombres;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("No se pudieron listar las llaves", e);
        }
    }

    /**
     * Codigo HTTP de escribir en una ruta con un token dado, sin lanzar excepcion.
     *
     * <p>Las pruebas de politica necesitan el CODIGO, no el resultado: un 403 es
     * el exito que buscan. Devolverlo tal cual evita que la prueba tenga que
     * interpretar el texto de un error, que cambia entre versiones.
     */
    public static int write(String ruta, String body, String token) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url() + ruta))
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            return HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("No se pudo llamar a " + ruta, e);
        }
    }

    private static JsonNode request(String ruta, String body, String token) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url() + ruta))
                .header("X-Vault-Token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> r = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("El KMS respondio " + r.statusCode()
                        + " a " + ruta + ": " + r.body());
            }
            return JSON.readTree(r.body());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("No se pudo llamar a " + ruta, e);
        }
    }
}
