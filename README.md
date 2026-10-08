# aliasdirectory-main-api

API principal del Directorio Centralizado de Alias: F1a, F1b, F2, F4, F5 y F7.

No expone endpoints HTTP hacia los bancos. Consume las colas de RabbitMQ que alimenta
`aliasdirectory-dispatch-api`, resuelve contra el padron y responde por la misma via.

## Depende de

- **`aliasdirectory-messaging`** — el contrato ISO 20022. Mientras no haya Nexus, hay que
  instalarlo a mano: `cd ..\aliasdirectory-messaging && .\mvnw install`

## Levantar en local

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

En PowerShell el `-D` va entre comillas: `"-Dspring-boot.run.profiles=local"`.

## Pruebas

```bash
./mvnw test                                # rapidas; excluye el grupo `integration`
./mvnw "-Dexcluded.test.groups=" test      # todas: necesitan Docker para Testcontainers
```

## Cuidado al desplegar

Los valores del enum `Operation` viajan dentro del mensaje AMQP y este servicio los
compara como cadena. **Este repo y `aliasdirectory-dispatch-api` se despliegan juntos**:
escalonado, un dispatch nuevo contra un main-api viejo responde 501.
