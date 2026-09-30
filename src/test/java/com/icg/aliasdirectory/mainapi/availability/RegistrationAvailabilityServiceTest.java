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
 * F1 extremo a extremo dentro del proceso: XML de entrada real, respuesta
 * validada contra el XSD.
 *
 * <p>La solicitud NO está escrita a mano aquí: se carga del ejemplo que vive en
 * el repositorio de esquemas, el mismo que valida contra el perfil. Un XML
 * inventado en una prueba termina probando que el código acepta lo que el propio
 * autor imaginó, no lo que el esquema exige.
 *
 * <p>Y la respuesta se valida contra el perfil de verdad. Sin eso, una prueba
 * puede pasar con un mensaje que ningún banco podría leer.
 */
class RegistrationAvailabilityServiceTest {

    private static final String YO = "GTCOGTGC";
    private static final String OTRO = "INDLGTGC";

    /** El ejemplo del repo: dos alias en una sola solicitud, con DPI. */
    private static final String EJEMPLO =
            "esquemas/ejemplos/perfiles/disponibilidad-registro/solicitud.xml";

    private final MessageValidator validator = new MessageValidator();
    private final MessageSerializer serializer = new MessageSerializer();

    private byte[] request;

    /** Repositorio de mentira: devuelve lo que se le diga, por índice de alias. */
    private RegistryRepository registryReturning(Map<String, List<ActiveRegistration>> porAlias,
            BlindIndex index) {
        return new RegistryRepository(null) {
            @Override
            public List<ActiveRegistration> activeRegistrations(String tipo, byte[] aliasBidx,
                    byte[] dpiBidx) {
                for (var e : porAlias.entrySet()) {
                    if (java.util.Arrays.equals(index.ofAlias(e.getKey()), aliasBidx)) {
                        return e.getValue();
                    }
                }
                return List.of();
            }
        };
    }

    private BlindIndex index() {
        // Llave fija de prueba: aquí no protege nada, y que sea determinista es
        // lo que permite preparar el padrón de mentira por valor de alias.
        return new LocalHmacBlindIndex(Base64.getEncoder().encodeToString(
                "llave-de-prueba-de-32-bytes-1234".getBytes(StandardCharsets.UTF_8)), 1);
    }

    /** Quita los prefijos de elemento para poder afirmar sobre la forma del mensaje. */
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
    @DisplayName("la solicitud del repo valida contra su perfil")
    void laEntradaEsValida() {
        assertThat(validator.validate(request, Profile.REGISTRATION_AVAILABILITY_REQUEST))
                .isEmpty();
    }

    @Test
    @DisplayName("responde un Rpt por cada Vrfctn, correlacionado, y valida contra el XSD")
    void respondeYValida() {
        var idx = index();
        var servicio = new RegistrationAvailabilityService(serializer, idx,
                registryReturning(Map.of(
                        // El primero está en otra entidad con otro DPI: conflicto.
                        "+50244444444", List.of(new ActiveRegistration(OTRO, true, false)),
                        // El segundo, en cuarentena.
                        "+50255556666", List.of(new ActiveRegistration(YO, false, true))), idx),
                "ICGSGTGC");

        byte[] response = servicio.handle(request, YO);

        var errores = validator.validate(response, Profile.REGISTRATION_AVAILABILITY_RESPONSE);
        assertThat(errores).as("la respuesta tiene que validar contra el perfil").isEmpty();

        // Se compara SIN prefijos: el marshaller los elige por su cuenta (sale
        // <ns3:OrgnlId>) y el prefijo no es parte del contrato —lo es el espacio
        // de nombres, que la validación de arriba ya comprobó—. Una aserción
        // atada al prefijo se rompería el día que JAXB los numere distinto, sin
        // que nada real hubiera cambiado.
        String xml = withoutPrefixes(response);
        assertThat(xml).contains("<OrgnlId>VRF-0001</OrgnlId>")
                       .contains("<OrgnlId>VRF-0002</OrgnlId>")
                       .contains("ACTIVE_OTHER_BANK_DIFF_DPI")
                       .contains("QUARANTINE")
                       // El destinatario es el banco que selló Dispatch.
                       .contains("<BICFI>" + YO + "</BICFI>");
    }

    @Test
    @DisplayName("el alias disponible se responde sin Rsn, y también valida")
    void disponibleValida() {
        var idx = index();
        var servicio = new RegistrationAvailabilityService(serializer, idx,
                registryReturning(Map.of(), idx), "ICGSGTGC");

        byte[] response = servicio.handle(request, YO);

        assertThat(validator.validate(response, Profile.REGISTRATION_AVAILABILITY_RESPONSE))
                .isEmpty();
        assertThat(withoutPrefixes(response))
                .doesNotContain("<Rsn>")
                .contains("<Vrfctn>false</Vrfctn>");
    }

    @Test
    @DisplayName("la respuesta no lleva datos del titular ni de la cuenta")
    void noSeFiltranDatosDelTitular() {
        var idx = index();
        var servicio = new RegistrationAvailabilityService(serializer, idx,
                registryReturning(Map.of(
                        "+50244444444", List.of(new ActiveRegistration(OTRO, true, false))), idx),
                "ICGSGTGC");

        String xml = withoutPrefixes(servicio.handle(request, YO));

        // El DPI viene en la solicitud; que no vuelva en la respuesta no es
        // casualidad, es lo que el perfil impide y lo que el servicio no arma.
        assertThat(xml).doesNotContain("1345986654379")
                       .doesNotContain("<Pty>")
                       .doesNotContain("IBAN");
    }

    @Test
    @DisplayName("el alias se normaliza: los mismos dígitos con espacios dan el mismo índice")
    void normalization() {
        var idx = index();
        assertThat(idx.ofAlias("+50244444444"))
                .isEqualTo(idx.ofAlias("+502 4444-4444"));
    }
}
