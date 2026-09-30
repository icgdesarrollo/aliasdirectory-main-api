package com.icg.aliasdirectory.mainapi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Un solo MySQL 8 para todas las pruebas del módulo (patrón singleton de Testcontainers).
 *
 * Reproduce el arranque local con los MISMOS artefactos que usa compose: el bootstrap de
 * {@code docker/mysql/init/} crea el esquema y las cuentas, Flyway migra con
 * {@code alias_directory_migrator} al levantar el contexto de Spring y los permisos de la
 * aplicación se conceden ejecutando {@code scripts/permisos-app.sh} dentro del contenedor.
 * Así la prueba detecta tanto una migración rota como un cambio en los scripts.
 */
/*
 * Es pública, igual que IntegrationOpenBao y por el mismo motivo: sus pruebas
 * ya no viven sólo en este paquete. RegistrationIntegrationTest está en «registro»
 * porque prueba ese paquete, y una clase de apoyo que sólo se ve desde aquí
 * obliga a elegir entre mover la prueba lejos de lo que prueba o duplicar el
 * montaje del contenedor. Ninguna de las dos vale la pena.
 */
public final class IntegrationMySql {

	/** Misma versión menor que compose.yaml y que la verificación manual de las migraciones. */
	public static final String IMAGEN = "mysql:8.0.46";

	public static final String BASE = "alias_directory";

	public static final String ROOT_USER = "root";

	public static final String ROOT_PASSWORD = "root_pruebas";

	public static final String MIGRATOR_USER = "alias_directory_migrator";

	public static final String MIGRATOR_PASSWORD = "migrator_pruebas";

	public static final String APP_USER = "alias_directory_app";

	public static final String APP_PASSWORD = "app_pruebas";

	public static final String AUDITOR_USER = "alias_directory_auditor";

	public static final String AUDITOR_PASSWORD = "auditor_pruebas";

	private static final String PERMISSIONS_SCRIPT_IN_CONTAINER = "/opt/permisos-app.sh";

	public static final MySQLContainer CONTAINER = create();

	private IntegrationMySql() {
	}

	private static MySQLContainer create() {
		Path raiz = InfraRepository.root();
		MySQLContainer container = new MySQLContainer(IMAGEN);
		container.withDatabaseName(BASE);
		// Con usuario root, Testcontainers sólo define MYSQL_ROOT_PASSWORD (no crea otra cuenta).
		container.withUsername(ROOT_USER);
		container.withPassword(ROOT_PASSWORD);
		container.withEnv("DB_NAME", BASE);
		container.withEnv("DB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD);
		container.withEnv("DB_APP_PASSWORD", APP_PASSWORD);
		container.withEnv("DB_AUDITOR_PASSWORD", AUDITOR_PASSWORD);
		// Modo 0644: el entrypoint de MySQL "sourcea" los .sh sin bit de ejecución, igual que
		// hace con la carpeta init que monta compose.
		container.withCopyFileToContainer(
				MountableFile.forHostPath(raiz.resolve("docker/mysql/init/01-bootstrap.sh"), 0644),
				"/docker-entrypoint-initdb.d/01-bootstrap.sh");
		container.withCopyFileToContainer(
				MountableFile.forHostPath(raiz.resolve("scripts/permisos-app.sh"), 0755),
				PERMISSIONS_SCRIPT_IN_CONTAINER);
		container.start();
		return container;
	}

	/**
	 * Ejecuta scripts/permisos-app.sh dentro del contenedor. Sólo tiene sentido después de que
	 * Flyway migró (las tablas deben existir); el script es idempotente.
	 */
	public static void applyApplicationPermissions() {
		ExecResult resultado;
		try {
			resultado = CONTAINER.execInContainer("bash", "-c",
					"MYSQL_PWD='" + ROOT_PASSWORD + "' MYSQL_CMD='mysql -uroot' bash " + PERMISSIONS_SCRIPT_IN_CONTAINER);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrumpido al aplicar permisos", e);
		}
		catch (IOException e) {
			throw new IllegalStateException("No se pudo ejecutar permisos-app.sh en el contenedor", e);
		}
		if (resultado.getExitCode() != 0) {
			throw new IllegalStateException("permisos-app.sh terminó con código " + resultado.getExitCode()
					+ "\nstdout:\n" + resultado.getStdout() + "\nstderr:\n" + resultado.getStderr());
		}
	}

	public static Connection connectAs(String usuario, String password) throws SQLException {
		return DriverManager.getConnection(CONTAINER.getJdbcUrl(), usuario, password);
	}

}
