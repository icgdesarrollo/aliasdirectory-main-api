############################
# Build stage
############################
# El contexto de build es la RAIZ del repositorio (multi-modulo):
#   docker build -f api-principal/Dockerfile -t directorio-alias-api-principal .
FROM maven:3.9-eclipse-temurin-25-alpine AS build

WORKDIR /workspace

# Primero solo los POM, para que la capa de dependencias se cachee aunque cambie el codigo.
COPY pom.xml .
COPY mensajeria-core/pom.xml mensajeria-core/
COPY mensajeria-spring-boot-starter/pom.xml mensajeria-spring-boot-starter/
COPY api-principal/pom.xml api-principal/
COPY dispatch/pom.xml dispatch/

# (la imagen base ya trae 'mvn' instalado, no se usa Maven Wrapper)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl api-principal -am dependency:go-offline

# Solo los modulos que este desplegable necesita (-am los arrastra).
COPY mensajeria-core/src mensajeria-core/src
COPY mensajeria-spring-boot-starter/ mensajeria-spring-boot-starter/
COPY api-principal/src api-principal/src

# Compila (incluye la generacion JAXB de mensajeria-core) y extrae las capas del JAR.
# Spring Boot 4.x removio jarmode=layertools; ahora es jarmode=tools con
# el subcomando 'extract --layers'.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests -pl api-principal -am clean package \
    && mkdir -p api-principal/target/extracted \
    && java -Djarmode=tools \
    -jar api-principal/target/*.jar extract --layers --launcher \
    --destination api-principal/target/extracted


############################
# Runtime stage — distroless Java 25, usuario nonroot
############################
# - Sin shell ni package manager: superficie de ataque minima (produccion regulada).
# - Usuario nonroot (uid 65532) preconfigurado en el tag :nonroot.
# - Java 25 solo existe en la variante debian13 de distroless.
# - Este modulo SI incluye actuator: las sondas de k8s usan
#   /actuator/health/liveness y /actuator/health/readiness.
FROM gcr.io/distroless/java25-debian13:nonroot

LABEL org.opencontainers.image.title="directorio-alias-api-principal"
LABEL org.opencontainers.image.description="Directorio Centralizado de Alias - API principal (Spring Boot 4)"
LABEL org.opencontainers.image.vendor="ICG"

WORKDIR /app

COPY --from=build --chown=nonroot:nonroot /workspace/api-principal/target/extracted/dependencies/ ./
COPY --from=build --chown=nonroot:nonroot /workspace/api-principal/target/extracted/spring-boot-loader/ ./
COPY --from=build --chown=nonroot:nonroot /workspace/api-principal/target/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=nonroot:nonroot /workspace/api-principal/target/extracted/application/ ./

EXPOSE 8080

# Explicito para dejar claro (y satisfacer el linter) que NO corre como root.
USER nonroot

# Distroless no tiene /bin/sh: ENTRYPOINT en exec-form obligatorio.
# Los flags JVM se pasan como argumentos (no via $JAVA_OPTS porque no hay shell).
# Zona horaria UTC a proposito: todo CreDtTm del contrato viaja en UTC con Z (T-6)
# y las bitacoras se correlacionan entre veinte bancos; una hora local en la JVM
# solo agrega una conversion implicita mas.
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-XX:+ExitOnOutOfMemoryError", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-Duser.timezone=UTC", \
    "org.springframework.boot.loader.launch.JarLauncher"]
