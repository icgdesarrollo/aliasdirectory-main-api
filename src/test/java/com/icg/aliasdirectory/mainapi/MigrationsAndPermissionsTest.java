package com.icg.aliasdirectory.mainapi;

import static com.icg.aliasdirectory.mainapi.IntegrationMySql.APP_PASSWORD;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.AUDITOR_PASSWORD;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.ROOT_PASSWORD;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.APP_USER;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.AUDITOR_USER;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.ROOT_USER;
import static com.icg.aliasdirectory.mainapi.IntegrationMySql.connectAs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Comprueba, sobre un MySQL 8 real, que las migraciones dejan el esquema esperado y que
 * los permisos de las cuentas hacen lo que dicen. Es la prueba que detecta que alguien
 * tocó una migración ya aplicada o rompió la matriz de privilegios, y corre en el
 * pipeline donde nadie mira el log.
 */
class MigrationsAndPermissionsTest extends MySqlBackedTest {

	/** ERROR 1142: "<comando> command denied to user". */
	private static final int COMMAND_DENIED = 1142;

	@Autowired
	private Flyway flyway;

	@Test
	void flywayAppliedAllMigrations() throws SQLException {
		List<String> aplicadas = new ArrayList<>();
		try (Connection conexion = connectAs(ROOT_USER, ROOT_PASSWORD);
				Statement statement = conexion.createStatement();
				ResultSet filas = statement.executeQuery(
						"SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank")) {
			while (filas.next()) {
				assertThat(filas.getBoolean("success")).as("migración %s", filas.getString("description")).isTrue();
				String version = filas.getString("version");
				aplicadas.add(version != null ? version : filas.getString("description"));
			}
		}
		// Las siete versionadas más la repetible (Flyway guarda su descripción con espacios).
		// 1.1.3 agrega row_hmac y row_hmac_ver al padrón (H-57): sólo columnas, ninguna
		// tabla ni vista nueva, por eso theSchemaHas23TablesAnd4Views no cambia.
		// La lista va escrita a mano a propósito: que agregar una migración rompa esta
		// prueba es el punto. Obliga a mirar si la nueva versión debía estar ahí, en vez
		// de que el esquema cambie sin que nadie lo note.
		assertThat(aplicadas).containsExactly(
				"1.0.0", "1.0.1", "1.0.2", "1.1.0", "1.1.1", "1.1.2", "1.1.3", "reference catalogs");
	}

	@Test
	void theSchemaHas23TablesAnd4Views() throws SQLException {
		Map<String, Integer> porTipo = new HashMap<>();
		try (Connection conexion = connectAs(ROOT_USER, ROOT_PASSWORD);
				Statement statement = conexion.createStatement();
				// flyway_schema_history es de Flyway, no del modelo: no cuenta.
				ResultSet filas = statement.executeQuery("""
						SELECT table_type, COUNT(*) AS n FROM information_schema.tables
						 WHERE table_schema = 'alias_directory' AND table_name <> 'flyway_schema_history'
						 GROUP BY table_type""")) {
			while (filas.next()) {
				porTipo.put(filas.getString("table_type"), filas.getInt("n"));
			}
		}
		assertThat(porTipo).containsOnly(Map.entry("BASE TABLE", 23), Map.entry("VIEW", 4));
	}

	@Test
	void theAppReadsAndWritesButCannotDeleteOrSeeCryptoKey() throws SQLException {
		try (Connection app = connectAs(APP_USER, APP_PASSWORD)) {
			assertThatCode(() -> execute(app, "SELECT * FROM participant_bank")).doesNotThrowAnyException();
			assertThatCode(() -> execute(app, "SELECT * FROM v_dispatch_routing")).doesNotThrowAnyException();

			// Si esto devolviera filas, los permisos estarían armados con REVOKE (problema 3).
			assertCommandDenied(app, "SELECT * FROM crypto_key");
			assertCommandDenied(app, "SELECT * FROM schema_version");
			// Sin DELETE en ninguna: toda baja es lógica.
			assertCommandDenied(app, "DELETE FROM participant_bank WHERE 1=0");
			assertCommandDenied(app, "DELETE FROM portal_audit_log WHERE 1=0");
			// Sin DDL.
			assertThatThrownBy(() -> execute(app, "CREATE TABLE intrusa (id INT PRIMARY KEY)"))
					.isInstanceOf(SQLException.class);
		}
	}

	@Test
	void theAuditorOnlyReadsTheAuditTables() throws SQLException {
		try (Connection auditor = connectAs(AUDITOR_USER, AUDITOR_PASSWORD)) {
			assertThatCode(() -> execute(auditor, "SELECT * FROM resolution_event")).doesNotThrowAnyException();
			assertThatCode(() -> execute(auditor, "SELECT * FROM resolution_event_detail")).doesNotThrowAnyException();
			assertThatCode(() -> execute(auditor, "SELECT * FROM portal_audit_log")).doesNotThrowAnyException();

			assertCommandDenied(auditor, "SELECT * FROM participant_bank");
			assertCommandDenied(auditor, "SELECT * FROM crypto_key");
			assertCommandDenied(auditor, "INSERT INTO portal_audit_log () VALUES ()");
		}
	}

	@Test
	void aSecondMigrateReappliesNothing() {
		// Equivale al "segundo arranque limpio": nada pendiente, checksums intactos.
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

	private static void execute(Connection conexion, String sql) throws SQLException {
		try (Statement statement = conexion.createStatement()) {
			statement.execute(sql);
		}
	}

	private static void assertCommandDenied(Connection conexion, String sql) {
		assertThatThrownBy(() -> execute(conexion, sql)).as(sql)
				.isInstanceOf(SQLException.class)
				.satisfies(e -> assertThat(((SQLException) e).getErrorCode()).as("código MySQL de: %s", sql)
						.isEqualTo(COMMAND_DENIED));
	}

}
