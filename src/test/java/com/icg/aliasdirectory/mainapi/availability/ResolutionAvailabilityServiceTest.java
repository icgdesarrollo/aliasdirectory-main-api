package com.icg.aliasdirectory.mainapi.availability;

import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;
import com.icg.aliasdirectory.messaging.validation.Profile;
import com.icg.aliasdirectory.messaging.validation.MessageValidator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F1 Resolución con el XML del repositorio y la respuesta validada contra su
 * perfil, igual que la de registro.
 */
class ResolutionAvailabilityServiceTest {

    private static final String YO = "GTCOGTGC";
    /** La cola por la que Dispatch entrega: va a la bitácora de consultas. */
    private static final String COLA = "alias.requests.gtcogtgc";
    private static final String DUENO = "INDLGTGC";
    private static final String ALIAS = "+50244444444";

    private static final String EJEMPLO =
            "esquemas/ejemplos/perfiles/disponibilidad-resolucion/solicitud.xml";

    private final MessageValidator validator = new MessageValidator();
    private final MessageSerializer serializer = new MessageSerializer();

    private byte[] request;

    private BlindIndex index() {
        return new LocalHmacBlindIndex(Base64.getEncoder().encodeToString(
                "llave-de-prueba-de-32-bytes-1234".getBytes(StandardCharsets.UTF_8)), 1);
    }

    private RegistryRepository registryReturning(Map<String, List<AliasRegistration>> porAlias,
            BlindIndex index) {
        return new RegistryRepository(null) {
            @Override
            public List<AliasRegistration> registrationsOfAlias(String tipo, byte[] aliasBidx) {
                for (var e : porAlias.entrySet()) {
                    if (java.util.Arrays.equals(index.ofAlias(e.getKey()), aliasBidx)) {
                        return e.getValue();
                    }
                }
                return List.of();
            }
        };
    }

    private static String withoutPrefixes(byte[] xml) {
        return new String(xml, StandardCharsets.UTF_8).replaceAll("ns\\d+:", "");
    }

    @BeforeEach
    void loadSample() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(EJEMPLO)) {
            assertThat(in).as("el ejemplo %s tiene que estar en el classpath", EJEMPLO).isNotNull();
            request = in.readAllBytes();
        }
    }

    @Test
    @DisplayName("la solicitud del repo valida contra su perfil, y no lleva DPI")
    void laEntradaEsValida() {
        assertThat(validator.validate(request, Profile.RESOLUTION_AVAILABILITY_REQUEST))
                .isEmpty();

        // Se comprueba sobre el mensaje desarmado, no buscando "<Pty>" en el
        // texto: el ejemplo lleva un comentario que MENCIONA Pty para explicar
        // por qué no está, y una aserción de cadena lo encuentra ahí y falla.
        var doc = serializer.parse(request,
                com.icg.aliasdirectory.messaging.iso.acmt023.Document.class);
        assertThat(doc.getIdVrfctnReq().getVrfctn())
                .allSatisfy(v -> assertThat(v.getPtyAndAcctId().getPty())
                        .as("esta consulta no lleva titular")
                        .isNull());
    }

    @Test
    @DisplayName("alias resoluble: Vrfctn=true con el banco dueño, y valida")
    void resoluble() {
        var idx = index();
        var servicio = new ResolutionAvailabilityService(serializer, idx,
                registryReturning(Map.of(ALIAS, List.of(new AliasRegistration(DUENO, true))), idx),
                // Sin KMS no existe el verificador de integridad (H-57); estas
                // pruebas ejercitan la regla de resolubilidad, no el sello.
                java.util.Optional.empty(), java.util.Optional.empty(), "ICGSGTGC");

        byte[] response = servicio.handle(request, YO, COLA);

        assertThat(validator.validate(response, Profile.RESOLUTION_AVAILABILITY_RESPONSE))
                .as("la respuesta tiene que validar contra el perfil").isEmpty();
        assertThat(withoutPrefixes(response))
                .contains("<Vrfctn>true</Vrfctn>")
                .contains("<BICFI>" + DUENO + "</BICFI>")
                .doesNotContain("<Rsn>");
    }

    @Test
    @DisplayName("alias en cuarentena: QUARANTINE, y valida")
    void cuarentena() {
        var idx = index();
        var servicio = new ResolutionAvailabilityService(serializer, idx,
                registryReturning(Map.of(ALIAS, List.of(new AliasRegistration(DUENO, false))), idx),
                // Sin KMS no existe el verificador de integridad (H-57); estas
                // pruebas ejercitan la regla de resolubilidad, no el sello.
                java.util.Optional.empty(), java.util.Optional.empty(), "ICGSGTGC");

        byte[] response = servicio.handle(request, YO, COLA);

        assertThat(validator.validate(response, Profile.RESOLUTION_AVAILABILITY_RESPONSE))
                .isEmpty();
        assertThat(withoutPrefixes(response))
                .contains("<Vrfctn>false</Vrfctn>")
                .contains("QUARANTINE");
    }

    @Test
    @DisplayName("la respuesta nunca lleva datos de cuenta")
    void sinDatosDeCuenta() {
        var idx = index();
        var servicio = new ResolutionAvailabilityService(serializer, idx,
                registryReturning(Map.of(ALIAS, List.of(new AliasRegistration(DUENO, true))), idx),
                // Sin KMS no existe el verificador de integridad (H-57); estas
                // pruebas ejercitan la regla de resolubilidad, no el sello.
                java.util.Optional.empty(), java.util.Optional.empty(), "ICGSGTGC");

        // Quién resuelve a qué cuenta lo contesta F3, que se audita como tal.
        assertThat(withoutPrefixes(servicio.handle(request, YO, COLA)))
                .doesNotContain("IBAN")
                .doesNotContain("<Ccy>")
                .doesNotContain("<Pty>");
    }

    @Test
    @DisplayName("un motivo de registro no valida contra este perfil")
    void theProfileForbidsRegistrationReasons() {
        // Cinturón y tirantes: la regla no puede producirlos (lo fija su propia
        // prueba) y el perfil tampoco los aceptaría. Se comprueba lo segundo con
        // un ejemplo inválido del repositorio, no con XML inventado aquí.
        byte[] malo;
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "esquemas/ejemplos/perfiles-invalidos/12-disp-resolucion-motivo-de-registro.xml")) {
            assertThat(in).isNotNull();
            malo = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThat(validator.validate(malo, Profile.RESOLUTION_AVAILABILITY_RESPONSE))
                .as("ACTIVE_SAME_BANK no debe validar contra el perfil de resolución")
                .isNotEmpty();
    }
}
