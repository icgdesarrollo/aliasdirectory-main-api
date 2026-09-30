package com.icg.aliasdirectory.mainapi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Localiza el repositorio de infraestructura, que es donde viven los artefactos que
 * las pruebas de integración ejercitan tal como se despliegan: el bootstrap y los
 * permisos de MySQL ({@code docker/mysql/init/}, {@code scripts/}) y las políticas
 * del KMS ({@code docker/openbao/politicas/}).
 *
 * <p>Antes cada prueba resolvía esas rutas por su cuenta —una subiendo hasta
 * {@code compose.yaml}, otra con un {@code ../} fijo—, y al separar el monorepo en
 * repositorios independientes las dos se rompieron por separado. Vive aquí para que
 * la próxima mudanza se arregle en un solo lugar.
 */
public final class InfraRepository {

	/** Repositorio hermano que guarda compose.yaml, docker/ y scripts/ desde la separación. */
	private static final String REPOSITORY = "aliasdirectory-infra";

	/** Lo que identifica a la carpeta: si está compose.yaml, está el resto. */
	private static final String MARKER = "compose.yaml";

	private InfraRepository() {
	}

	/**
	 * Raíz del repositorio de infraestructura.
	 *
	 * <p>La búsqueda sube desde el cwd y en cada nivel prueba las dos formas: el nivel
	 * mismo (monorepo, y el propio repo de infraestructura) y {@code aliasdirectory-infra/}
	 * dentro de él (repos separados, clonados como hermanos).
	 *
	 * <p>Si los repositorios no están clonados uno al lado del otro —lo normal en un
	 * agente de integración continua— la ruta se da explícitamente con
	 * {@code -Daliasdirectory.infra=/ruta/a/aliasdirectory-infra} o con la variable de
	 * entorno {@code ALIASDIRECTORY_INFRA}.
	 */
	public static Path root() {
		String configurada = System.getProperty("aliasdirectory.infra");
		if (configurada == null || configurada.isBlank()) {
			configurada = System.getenv("ALIASDIRECTORY_INFRA");
		}
		if (configurada != null && !configurada.isBlank()) {
			Path indicada = Paths.get(configurada).toAbsolutePath().normalize();
			if (!Files.exists(indicada.resolve(MARKER))) {
				throw new IllegalStateException(
						"aliasdirectory.infra apunta a " + indicada + ", que no contiene " + MARKER);
			}
			return indicada;
		}
		Path actual = Paths.get("").toAbsolutePath();
		for (Path p = actual; p != null; p = p.getParent()) {
			if (Files.exists(p.resolve(MARKER))) {
				return p;
			}
			Path infra = p.resolve(REPOSITORY);
			if (Files.exists(infra.resolve(MARKER))) {
				return infra;
			}
		}
		throw new IllegalStateException("No se encontró " + MARKER + " subiendo desde " + actual
				+ " (ni en él, ni en un " + REPOSITORY + "/ de cada nivel)."
				+ " Clone " + REPOSITORY + " junto a este repositorio"
				+ " o indique su ruta con -Daliasdirectory.infra=<ruta>.");
	}

	/**
	 * Un archivo dentro del repositorio de infraestructura, comprobando que exista.
	 *
	 * @param motivo qué prueba lo necesita y por qué; va en el mensaje de error para que
	 *               el fallo diga qué falta en vez de sólo dónde no estaba
	 */
	public static Path file(String rutaRelativa, String motivo) {
		Path archivo = root().resolve(rutaRelativa);
		if (!Files.exists(archivo)) {
			throw new IllegalStateException("No se encontró " + archivo + ". " + motivo);
		}
		return archivo;
	}

}
