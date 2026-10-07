package com.icg.aliasdirectory.mainapi.consumer;

import com.icg.aliasdirectory.mainapi.availability.RegistrationAvailabilityService;
import com.icg.aliasdirectory.mainapi.cancellation.CancellationService;
import com.icg.aliasdirectory.mainapi.customerquery.CustomerQueryService;
import com.icg.aliasdirectory.mainapi.resolution.ResolutionService;
import com.icg.aliasdirectory.mainapi.uuidquery.UuidQueryService;
import com.icg.aliasdirectory.mainapi.registration.RegistrationService;
import com.icg.aliasdirectory.mainapi.availability.ResolutionAvailabilityService;
import com.icg.aliasdirectory.messaging.error.ErrorBody;
import com.icg.aliasdirectory.messaging.error.ResponseCode;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Consume las solicitudes que Dispatch encola y contesta por el mismo canal.
 *
 * <p>Lee el contexto de las propiedades del mensaje, no del cuerpo. Detrás de la
 * cola ya no hay banco al otro lado del socket, así que el consumidor no puede
 * volver a validar quién llamó: confía en lo que Dispatch selló —que lo derivó
 * del certificado en el borde— y nunca en el {@code Assgnr} del XML, que lo
 * escribe el propio banco.
 *
 * <p>El estado HTTP viaja de vuelta en una propiedad. Dispatch lo transporta sin
 * abrir el cuerpo; quien sabe si la operación fue un 200, un 201 o un 409 es
 * este lado, que es el que consultó el padrón.
 *
 * <p>De las siete operaciones están implementadas cuatro: las dos de
 * disponibilidad, el registro (F2) y la consulta por cliente (F4). Las tres que
 * faltan —resolución y las dos bajas— contestan 501 explícitamente en vez de
 * caer con un error genérico: un banco que llame a una operación que todavía no
 * existe merece enterarse de eso y no de un tiempo de espera agotado.
 *
 * <p>Todos los códigos, el de las que funcionan y el de las que no, salen del
 * catálogo del Anexo §6 ({@code ResponseCode}), que es también de donde sale el
 * estado HTTP con el que viajan: así el cuerpo y la cabecera no pueden decir
 * cosas distintas.
 */
@Component
public class RequestConsumer {

    private static final Logger log = LoggerFactory.getLogger(RequestConsumer.class);

    /** Las mismas propiedades que pone Dispatch. Un cambio aquí se rompe allá. */
    static final String P_OPERATION = "icg-operacion";
    static final String P_BIC = "icg-bic";
    static final String P_HTTP_STATUS = "icg-http-estado";

    private final RegistrationAvailabilityService registrationAvailability;
    private final ResolutionAvailabilityService resolutionAvailability;
    /**
     * Vacíos cuando el KMS está apagado.
     *
     * <p>El registro cifra y la consulta por cliente descifra, así que ninguno de
     * los dos puede existir sin KMS —ver {@code RegistrationService} y
     * {@code CustomerQueryService}—. Se inyectan como Optional y no como
     * dependencia dura para que la aplicación siga arrancando sin KMS, que es un
     * requisito: una compilación recién clonada tiene que poder levantar sin
     * montar un OpenBao. Las dos consultas de disponibilidad no lo necesitan y
     * siguen funcionando.
     */
    private final java.util.Optional<RegistrationService> registration;

    private final java.util.Optional<CustomerQueryService> customerQuery;

    private final java.util.Optional<ResolutionService> resolution;

    private final java.util.Optional<CancellationService> cancellation;

    private final java.util.Optional<UuidQueryService> uuidQuery;

    public RequestConsumer(RegistrationAvailabilityService registrationAvailability,
            ResolutionAvailabilityService resolutionAvailability,
            java.util.Optional<RegistrationService> registration,
            java.util.Optional<CustomerQueryService> customerQuery,
            java.util.Optional<ResolutionService> resolution,
            java.util.Optional<CancellationService> cancellation,
            java.util.Optional<UuidQueryService> uuidQuery) {
        this.registrationAvailability = registrationAvailability;
        this.resolutionAvailability = resolutionAvailability;
        this.registration = registration;
        this.customerQuery = customerQuery;
        this.resolution = resolution;
        this.cancellation = cancellation;
        this.uuidQuery = uuidQuery;
    }

    @RabbitListener(queues = "#{'${icg.consumer.queues}'.split(',')}")
    public Message handle(Message request) {
        MessageProperties props = request.getMessageProperties();
        String operation = header(props, P_OPERATION);
        String bic = header(props, P_BIC);

        // Se registra quién pidió qué, y NUNCA el cuerpo: lleva alias, DPI y
        // números de cuenta (regla T-8). El tamaño sí, que sirve de diagnóstico
        // y no revela nada.
        log.info("solicitud recibida: banco={} operacion={} cola={} bytes={}",
                bic, operation, props.getConsumerQueue(),
                request.getBody() == null ? 0 : request.getBody().length);

        try {
            return switch (operation) {
                case "REGISTRATION_AVAILABILITY" ->
                        response(registrationAvailability.handle(request.getBody(), bic), 200);
                case "RESOLUTION_AVAILABILITY" ->
                        response(resolutionAvailability.handle(request.getBody(), bic), 200);
                // El codigo HTTP lo trae la respuesta: 201 si el alias quedo
                // registrado, 409 si estaba tomado. No se fija aqui a proposito —
                // antes estaba fijo en 201 y los rechazos salian con una cabecera
                // que decia lo contrario que el cuerpo (PTRAD-688).
                case "REGISTRATION" -> registration
                        .map(s -> {
                            var r = s.handle(request.getBody(), bic);
                            return response(r.body(), r.httpStatus());
                        })
                        .orElseGet(() -> error(ResponseCode.KMS_UNAVAILABLE, "registro de alias"));
                // F4. La lista vacia es 200 y no 404: «este cliente no tiene
                // alias» es una respuesta legitima, no una falla.
                case "QUERY_BY_CUSTOMER" -> customerQuery
                        .map(s -> response(s.handle(request.getBody(), bic), 200))
                        .orElseGet(() -> error(ResponseCode.KMS_UNAVAILABLE, "consulta por cliente"));
                // F3. El codigo lo trae la respuesta: 200 si resolvio, 404 si el
                // alias no existe, 409 si esta en cuarentena.
                case "RESOLUTION" -> resolution
                        .map(s -> {
                            var r = s.handle(request.getBody(), bic);
                            return response(r.body(), r.httpStatus());
                        })
                        .orElseGet(() -> error(ResponseCode.KMS_UNAVAILABLE, "resolucion de alias"));
                // F5. Los dos alcances entran por el mismo servicio: el CxlScp
                // viene en el cuerpo y el endpoint solo lo confirma.
                case "CANCEL_OWN_BANK", "CANCEL_ALL_BANKS" -> cancellation
                        .map(s -> {
                            var r = s.handle(request.getBody(), bic);
                            return response(r.body(), r.httpStatus());
                        })
                        .orElseGet(() -> error(ResponseCode.KMS_UNAVAILABLE, "baja de alias"));
                // F7, interna.
                case "QUERY_BY_UUID" -> uuidQuery
                        .map(s -> {
                            var r = s.handle(request.getBody(), bic);
                            return response(r.body(), r.httpStatus());
                        })
                        .orElseGet(() -> error(ResponseCode.KMS_UNAVAILABLE, "consulta por UUID"));
                default -> error(ResponseCode.NOT_IMPLEMENTED, "operacion " + operation);
            };
        } catch (UuidQueryService.UuidNotFoundException e) {
            // El AliasUUID no existe. Es el 404 que el Anexo §6 fija para F7, y el
            // unico 404 de las consultas: preguntar por un identificador que no
            // existe es distinto de una lista vacia.
            log.info("consulta por uuid sin resultado: banco={}", bic);
            return error(ResponseCode.UUID_NOT_FOUND, e.getMessage());
        } catch (CancellationService.ScopeNotAuthorizedException e) {
            // El banco existe y el mensaje es valido; lo que falta es la
            // autorizacion elevada del Anexo F5 para dar de baja alias de otras
            // entidades. Es 403 y no 400: la solicitud esta bien formada.
            log.warn("alcance no autorizado: banco={} operacion={}", bic, operation);
            return error(ResponseCode.FORBIDDEN_SCOPE, e.getMessage());
        } catch (CancellationService.RegistrationNotFoundException e) {
            log.info("baja sobre registro inexistente: banco={}", bic);
            return error(ResponseCode.CANCELLATION_NOT_FOUND, e.getMessage());
        } catch (ResolutionService.InvalidResolutionException
                | CancellationService.InvalidCancellationException
                | UuidQueryService.InvalidUuidQueryException e) {
            // Paso el XSD y aun asi no sirve para la operacion que se pidio. Es
            // error del banco, no nuestro: 400 y no 500.
            log.warn("solicitud mal formada: banco={} operacion={} motivo={}",
                    bic, operation, e.getMessage());
            return error(ResponseCode.INVALID_CRITERIA, e.getMessage());
        } catch (CustomerQueryService.InvalidQueryException e) {
            // Pasó el XSD pero no trae el criterio que F4 necesita: el esquema deja
            // SchCrit como elección entre Ownr (F4) y AliasUUID (F7), así que una
            // consulta de F7 mandada a este endpoint es válida y no es de aquí.
            // Es un error del banco, no nuestro: 400 y no 500.
            log.warn("consulta por cliente mal formada: banco={} motivo={}", bic, e.getMessage());
            return error(ResponseCode.INVALID_CRITERIA, e.getMessage());
        } catch (MessageSerializer.UnreadableMessageException e) {
            // Dispatch ya validó contra el XSD, así que llegar aquí significa que
            // el mensaje pasó el esquema y aun así no se pudo desarmar. Es un
            // defecto nuestro, no del banco, y por eso es 500 y no 400.
            log.error("mensaje que pasó el esquema y no se pudo desarmar: banco={} operacion={}",
                    bic, operation, e);
            return error(ResponseCode.INTERNAL_ERROR, "el mensaje paso el esquema y no se pudo desarmar");
        } catch (RuntimeException e) {
            log.error("fallo atendiendo: banco={} operacion={}", bic, operation, e);
            return error(ResponseCode.INTERNAL_ERROR);
        }
    }

    private static Message response(byte[] body, int status) {
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_XML)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setHeader(P_HTTP_STATUS, status)
                .build();
    }

    /**
     * Cuerpo de error propio del directorio, el mismo que emite Dispatch.
     *
     * <p>No va en acmt.024: una respuesta ISO válida diría que la consulta se
     * atendió, y no se atendió. Tampoco lleva el mensaje del banco de vuelta.
     *
     * <p>Antes este lado armaba su propio XML —{@code Codigo} y {@code Detalle},
     * sin {@code Timestamp}, que el Anexo §6 exige— mientras Dispatch armaba
     * otro distinto. Al banco le llegaba una forma u otra según dónde hubiera
     * fallado. Ahora los dos usan {@link ErrorBody} y el estado HTTP sale del
     * mismo catálogo que el código, así que no se pueden desalinear.
     */
    private static Message error(ResponseCode code) {
        return response(ErrorBody.of(code), code.httpStatus());
    }

    private static Message error(ResponseCode code, String detail) {
        return response(ErrorBody.of(code, detail), code.httpStatus());
    }

    private static String header(MessageProperties props, String nombre) {
        Object value = props.getHeader(nombre);
        return value == null ? "(ausente)" : value.toString();
    }
}
