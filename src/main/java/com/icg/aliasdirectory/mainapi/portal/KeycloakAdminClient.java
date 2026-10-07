package com.icg.aliasdirectory.mainapi.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cliente de la Admin API de Keycloak para el alta de agentes (E15-D04-3).
 *
 * <p>Se autentica con {@code client_credentials} de un cliente dedicado del
 * realm, al que se le concede únicamente {@code manage-users} y
 * {@code view-users} de {@code realm-management}. No se usa una cuenta humana de
 * administrador: una credencial de servicio se rota sin depender de que una
 * persona siga en la organización, y su alcance se audita en un solo lugar.
 *
 * <p><b>El cliente no decide a qué grupo entra el usuario.</b> El grupo es el
 * del banco del administrador que ejecuta el alta, y lo impone
 * {@link AdminUserService}. Aquí sólo se ejecuta lo que ya viene decidido.
 *
 * <p>El token de servicio se guarda en memoria hasta poco antes de expirar. Es
 * un dato vivo, nunca se escribe en el log ni en la bitácora.
 */
@ConditionalOnProperty(name = "icg.portal.keycloak.enabled", havingValue = "true")
@Component
public class KeycloakAdminClient {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminClient.class);

    /** Margen para no usar un token que expira mientras viaja la petición. */
    private static final int EXPIRY_MARGIN_SECONDS = 30;

    private final RestClient http;
    private final String realm;
    private final String clientId;
    private final String clientSecret;

    private String cachedToken;
    private Instant cachedTokenExpiry = Instant.EPOCH;

    // RestClient.builder() y no el bean RestClient.Builder: la autoconfiguracion
    // que lo publica no esta activa en esta aplicacion, igual que TransitClient
    // construye su propio HttpClient en vez de depender del contexto.
    public KeycloakAdminClient(
            @Value("${icg.portal.keycloak.base-url}") String baseUrl,
            @Value("${icg.portal.keycloak.realm}") String realm,
            @Value("${icg.portal.keycloak.client-id}") String clientId,
            @Value("${icg.portal.keycloak.client-secret}") String clientSecret) {
        this.http = RestClient.builder().baseUrl(stripTrailingSlash(baseUrl)).build();
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Crea el usuario y devuelve su {@code id}, que es el {@code sub} que llegará
     * en el token y lo que se guarda en {@code portal_user.idp_subject}.
     *
     * <p>Nace con {@code UPDATE_PASSWORD} pendiente: la contraseña temporal sólo
     * sirve para el primer ingreso.
     */
    public String createUser(NewUser user) {
        var payload = Map.of(
                "username", user.username(),
                "email", user.email(),
                "firstName", user.firstName(),
                "lastName", user.lastName(),
                "enabled", true,
                "emailVerified", false,
                "requiredActions", List.of("UPDATE_PASSWORD"),
                "credentials", List.of(Map.of(
                        "type", "password",
                        "value", user.temporaryPassword(),
                        "temporary", true)));

        try {
            var response = http.post()
                    .uri("/admin/realms/{realm}/users", realm)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();

            var location = response.getHeaders().getLocation();
            if (location == null) {
                throw new PortalException(502, "ICG-502-IDP",
                    "Keycloak creó el usuario pero no devolvió su identificador.");
            }
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw new PortalException(409, "ICG-409-USUARIO-EXISTE",
                        "Ya existe un usuario con ese nombre o correo en el proveedor de "
                                + "identidad.");
            }
            throw idpFailure("crear el usuario", e);
        }
    }

    public void updateUser(String idpSubject, String email, String firstName, String lastName) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/users/{id}", realm, idpSubject)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken())
                    .body(Map.of("email", email, "firstName", firstName, "lastName", lastName))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw idpFailure("actualizar el usuario", e);
        }
    }

    /** Deshabilitar en el IdP corta el ingreso aunque el token actual siga vivo. */
    public void setEnabled(String idpSubject, boolean enabled) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/users/{id}", realm, idpSubject)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + serviceToken())
                    .body(Map.of("enabled", enabled))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw idpFailure("cambiar el estado del usuario", e);
        }
    }

    /** Cierra las sesiones abiertas: sin esto, un token ya emitido sigue sirviendo. */
    public void logoutUser(String idpSubject) {
        try {
            http.post()
                    .uri("/admin/realms/{realm}/users/{id}/logout", realm, idpSubject)
                    .header("Authorization", "Bearer " + serviceToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // No es motivo para deshacer la baja: el usuario ya está
            // deshabilitado y su siguiente renovación de token fallará.
            log.warn("idp: no se pudieron cerrar las sesiones del usuario ({})",
                    e.getStatusCode());
        }
    }

    public void addToGroup(String idpSubject, String groupId) {
        try {
            http.put()
                    .uri("/admin/realms/{realm}/users/{userId}/groups/{groupId}", realm,
                            idpSubject, groupId)
                    .header("Authorization", "Bearer " + serviceToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw idpFailure("asignar el grupo de la entidad", e);
        }
    }

    /** Busca el grupo del banco por su ruta {@code /bancos/<BIC>}. */
    public Optional<String> groupIdByBic(String bic) {
        try {
            var groups = http.get()
                    .uri(uri -> uri.path("/admin/realms/{realm}/groups")
                            .queryParam("search", bic)
                            .queryParam("populateHierarchy", true)
                            .build(realm))
                    .header("Authorization", "Bearer " + serviceToken())
                    .retrieve()
                    .body(List.class);
            return findByName(groups, bic);
        } catch (RestClientResponseException e) {
            throw idpFailure("consultar los grupos", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<String> findByName(List<?> groups, String name) {
        if (groups == null) {
            return Optional.empty();
        }
        for (Object raw : groups) {
            if (!(raw instanceof Map<?, ?> group)) {
                continue;
            }
            if (name.equals(group.get("name"))) {
                return Optional.ofNullable((String) group.get("id"));
            }
            Object children = group.get("subGroups");
            if (children instanceof List<?> list) {
                var found = findByName(list, name);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private synchronized String serviceToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return cachedToken;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        try {
            var body = http.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (body == null || body.get("access_token") == null) {
                throw new PortalException(502, "ICG-502-IDP",
                    "El proveedor de identidad no devolvió un token de servicio.");
            }
            cachedToken = (String) body.get("access_token");
            int expiresIn = body.get("expires_in") instanceof Number n ? n.intValue() : 60;
            cachedTokenExpiry = Instant.now()
                    .plusSeconds(Math.max(1, expiresIn - EXPIRY_MARGIN_SECONDS));
            return cachedToken;
        } catch (RestClientResponseException e) {
            // El secreto del cliente no se registra ni en el mensaje ni en el log.
            log.error("idp: fallo autenticando la cuenta de servicio ({})", e.getStatusCode());
            throw new PortalException(502, "ICG-502-IDP",
                    "No se pudo autenticar contra el proveedor de identidad.");
        }
    }

    private PortalException idpFailure(String action, RestClientResponseException e) {
        // El cuerpo va al log del servidor porque sin él no hay forma de saber
        // qué regla incumplió la petición: Keycloak devuelve 400 tanto por una
        // política de contraseña como por un correo mal formado. Es seguro: el
        // error describe la validación, no el valor que la incumplió, y nunca
        // incluye la contraseña enviada. Al cliente se le sigue diciendo nada.
        log.error("idp: fallo al {} ({}): {}", action, e.getStatusCode(),
                e.getResponseBodyAsString());
        return new PortalException(502, "ICG-502-IDP",
                    "El proveedor de identidad rechazó la operación.");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Datos de alta. {@code temporaryPassword} nunca se registra en bitácora. */
    public record NewUser(String username, String email, String firstName, String lastName,
            String temporaryPassword) {
    }
}
