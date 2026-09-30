package com.icg.aliasdirectory.mainapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Arranca contra el MySQL de pruebas: /actuator/health incluye el indicador `db`, que
 * consulta como alias_directory_app, así que también cubre que la aplicación llegue a la base.
 */
class HealthEndpointTest extends MySqlBackedTest {

	@Autowired
	private Environment entorno;

	@Test
	void theApplicationStartsAndHealthProbesReportUp() {
		String base = "http://localhost:" + entorno.getProperty("local.server.port") + "/actuator/health";
		RestClient cliente = RestClient.create();

		assertThat(cliente.get().uri(base).retrieve().body(String.class)).contains("\"status\":\"UP\"");
		// Las dos rutas que consultan las sondas del Deployment de k8s.
		assertThat(cliente.get().uri(base + "/liveness").retrieve().body(String.class)).contains("\"status\":\"UP\"");
		assertThat(cliente.get().uri(base + "/readiness").retrieve().body(String.class)).contains("\"status\":\"UP\"");
	}

}
