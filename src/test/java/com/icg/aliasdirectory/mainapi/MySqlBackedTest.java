package com.icg.aliasdirectory.mainapi;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base de las pruebas que levantan el contexto completo contra el MySQL de
 * {@link IntegrationMySql}. Todas comparten la misma configuración, así que Spring
 * reutiliza un único contexto (y Flyway migra una sola vez).
 *
 * <p>Van etiquetadas como «integration» y quedan FUERA de {@code mvn test} por
 * omisión. No es que importen menos: es que necesitan Docker, y una compilación
 * que no corre sin Docker deja de correr en el pipeline, en la máquina de quien
 * acaba de clonar y en la de cualquiera que tenga Docker Desktop apagado. Las
 * pruebas que no dependen de nada externo tienen que poder correr siempre.
 *
 * <p>Para correr también estas:  mvn test -Dexcluded.test.groups=
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class MySqlBackedTest {

	@DynamicPropertySource
	static void containerConnection(DynamicPropertyRegistry registration) {
		registration.add("spring.datasource.url", IntegrationMySql.CONTAINER::getJdbcUrl);
		registration.add("spring.datasource.username", () -> IntegrationMySql.APP_USER);
		registration.add("spring.datasource.password", () -> IntegrationMySql.APP_PASSWORD);
		registration.add("spring.flyway.user", () -> IntegrationMySql.MIGRATOR_USER);
		registration.add("spring.flyway.password", () -> IntegrationMySql.MIGRATOR_PASSWORD);
		// Llave del índice ciego. Fija y sin secreto: aquí no protege nada, y que
		// sea determinista es lo que permite calcular el mismo HMAC en el montaje
		// de la prueba y en el código bajo prueba.
		registration.add("icg.blind-index.key",
				() -> java.util.Base64.getEncoder().encodeToString(
						"llave-de-prueba-de-32-bytes-1234".getBytes(
								java.nio.charset.StandardCharsets.UTF_8)));
		// El consumidor no arranca: estas pruebas son de base de datos y sin broker
		// el contenedor de escucha reintentaría cada cinco segundos llenando el log.
		registration.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
		// AMQPS apagado. No es cosmético: con spring.rabbitmq.ssl.enabled=true el
		// keystore se abre al CREAR el bean de la fábrica de conexiones, no al
		// conectar, así que el contexto de Spring ni siquiera arranca si el archivo
		// no está. Y no tiene por qué estar: los certificados los genera un script
		// que nadie debería tener que correr para compilar.
		registration.add("spring.rabbitmq.ssl.enabled", () -> "false");
		// Y el indicador de salud del broker queda fuera. Estas pruebas son de base de
		// datos: sin apagarlo, /actuator/health intenta abrir una conexión AMQP en
		// texto plano contra el 5671, que desde E05-D03 es un listener TLS, y
		// HealthEndpointTest da 503 por un broker que no tiene nada que ver con lo que
		// esa prueba comprueba.
		registration.add("management.health.rabbit.enabled", () -> "false");
	}

	/**
	 * Con PER_CLASS este método corre sobre la instancia ya inyectada, es decir, con el
	 * contexto levantado y Flyway aplicado: recién entonces existen las tablas sobre las
	 * que el script concede permisos. Hasta ese momento alias_directory_app no puede ni entrar
	 * al esquema, igual que en local antes de correr scripts/permisos-app.sh.
	 */
	@BeforeAll
	void grantApplicationPermissions() {
		IntegrationMySql.applyApplicationPermissions();
	}

}
