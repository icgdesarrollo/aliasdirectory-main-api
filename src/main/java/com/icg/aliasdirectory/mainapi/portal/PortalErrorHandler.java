package com.icg.aliasdirectory.mainapi.portal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Traduce los fallos del portal a un cuerpo JSON único.
 *
 * <p>Ningún mensaje que sale de aquí lleva el alias, el IBAN ni el IdCliente
 * (regla T-8): un texto de error termina en la pantalla del agente, en el
 * registro del navegador y a veces en una captura pegada en un ticket.
 */
@RestControllerAdvice(assignableTypes = {PortalController.class, PortalMeController.class,
        AdminController.class, AuditController.class})
public class PortalErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(PortalErrorHandler.class);

    @ExceptionHandler(PortalException.class)
    ResponseEntity<PortalDto.ErrorView> onPortalException(PortalException e) {
        return ResponseEntity.status(e.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalDto.ErrorView(e.code(), e.getMessage(), List.of()));
    }

    @ExceptionHandler(CurrentPortalUser.NotEnrolledException.class)
    ResponseEntity<PortalDto.ErrorView> onNotEnrolled(CurrentPortalUser.NotEnrolledException e) {
        // 403 y no 401: el token es válido, lo que falta es el alta en el
        // portal. Un 401 haría que el navegador reintentara la autenticación en
        // un bucle que nunca se resuelve solo.
        log.info("portal: token válido sin usuario activo");
        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalDto.ErrorView("ICG-403-SIN-ALTA",
                        "Su usuario no está dado de alta en el portal o fue desactivado.",
                        List.of()));
    }

    @ExceptionHandler(CurrentPortalUser.MissingPermissionException.class)
    ResponseEntity<PortalDto.ErrorView> onMissingPermission(
            CurrentPortalUser.MissingPermissionException e) {
        log.info("portal: {}", e.getMessage());
        return ResponseEntity.status(403)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalDto.ErrorView("ICG-403-SIN-PERMISO",
                        "No tiene permiso para esta operación.", List.of()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<PortalDto.ErrorView> onUnexpected(Exception e) {
        // El detalle va al log del servidor y nunca al cuerpo: un stack trace o
        // un mensaje de SQL en la respuesta le cuenta al cliente cómo está hecho
        // el padrón.
        log.error("portal: fallo no controlado", e);
        return ResponseEntity.status(500)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PortalDto.ErrorView("ICG-500-ERROR-INTERNO",
                        "Ocurrió un error al procesar la solicitud.", List.of()));
    }
}
