package com.icg.aliasdirectory.mainapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * El relay de la bandeja de salida (E19-D08) es lo único programado de esta
 * aplicación, y por eso hace falta habilitar la planificación aquí: sin
 * {@code @EnableScheduling}, el {@code @Scheduled} de
 * {@code RegistryEventRelay} se ignora en silencio —el bean se crea, el método
 * nunca se llama— y los eventos del padrón se acumulan sin que nada avise.
 */
@EnableScheduling
@SpringBootApplication
public class MainApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(MainApiApplication.class, args);
	}

}
