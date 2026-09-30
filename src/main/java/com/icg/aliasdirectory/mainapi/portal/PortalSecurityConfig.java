package com.icg.aliasdirectory.mainapi.portal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Seguridad del BFF del portal.
 *
 * <p>Sólo cubre {@code /portal/**}. El resto de la aplicación —el consumidor de
 * colas y las sondas de actuator— no pasa por aquí: el consumidor no atiende
 * HTTP y las sondas las consulta Kubernetes desde dentro del clúster.
 *
 * <p>Sin sesión de servidor. Cada petición trae su token y se valida sola; una
 * cookie de sesión sólo agregaría una segunda identidad que mantener sincronizada
 * con la del IdP y un vector de CSRF que sin cookie no existe.
 *
 * <p>Toda respuesta sale con {@code no-store}. Lo que devuelve este BFF son datos
 * del padrón —teléfonos y cuentas de personas reales— y no puede quedar en la
 * caché del navegador de un puesto compartido de Call Center ni en un proxy
 * corporativo intermedio.
 */
@Configuration
public class PortalSecurityConfig {

    @ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "true")
    @Bean
    SecurityFilterChain portalFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/portal/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sin sesión ni cookies no hay CSRF que proteger, y el token
                // viaja en la cabecera Authorization, que el navegador no
                // adjunta solo desde otro sitio.
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers
                        .cacheControl(Customizer.withDefaults())
                        .addHeaderWriter(new StaticHeadersWriter(
                                HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate"))
                        .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "no-referrer")))
                .build();
    }

    /**
     * Orígenes que pueden llamar al BFF desde un navegador.
     *
     * <p>Hace falta porque el portal y el BFF viven en orígenes distintos —en
     * desarrollo, 5173 y 8082—, así que toda llamada va precedida de un preflight
     * que el navegador bloquea si el servidor no lo contesta.
     *
     * <p>Es una lista explícita y no un comodín: con {@code allowCredentials} el
     * comodín ni siquiera es legal, y aun sin él, abrir el BFF a cualquier origen
     * permitiría que una página cualquiera que el agente tenga abierta consulte
     * el padrón con la sesión de ese agente.
     */
    @ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "true")
    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${icg.portal.cors-origins:http://localhost:5173}") List<String> origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cache-Control",
                "Pragma"));
        config.setAllowCredentials(true);
        config.setMaxAge(1800L);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/portal/**", config);
        return source;
    }

    /**
     * Con el portal apagado, todo pasa.
     *
     * <p>No es una puerta abierta: sin el portal no hay ningún endpoint que
     * proteger —el consumidor de colas no atiende HTTP y lo único publicado son
     * las sondas de actuator—. Hace falta declararla porque Spring Security, al
     * estar en el classpath y no encontrar ninguna cadena, aplica la suya, que
     * pide basic auth con una contraseña que imprime en el log. Eso dejaría las
     * sondas de Kubernetes devolviendo 401 y el despliegue reiniciándose en
     * bucle por un portal que ni siquiera está encendido.
     */
    @ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "false", matchIfMissing = true)
    @Bean
    SecurityFilterChain openFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .build();
    }
}
