package com.icg.aliasdirectory.mainapi.portal;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Consulta de la bitácora del portal (E15-D05, Anexo §8.4).
 *
 * <p>Aquí sí viajan parámetros en la cadena de consulta, al revés que en
 * {@link PortalController}: lo que se filtra son fechas, un login y un tipo de
 * operación, no el teléfono ni la cuenta de nadie. Ninguno de estos valores es
 * un dato personal del titular de un alias.
 */
@ConditionalOnProperty(name = "icg.portal.enabled", havingValue = "true")
@RestController
@RequestMapping("/portal/audit")
public class AuditController {

    private final CurrentPortalUser currentUser;
    private final AuditService audit;

    public AuditController(CurrentPortalUser currentUser, AuditService audit) {
        this.currentUser = currentUser;
        this.audit = audit;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PortalDto.AuditView> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) String user,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) Integer limit,
            HttpServletRequest request) {
        var caller = currentUser.require();
        return audit.search(new PortalDto.AuditFilter(from, to, user, operation, limit), caller,
                request);
    }

    @GetMapping(path = "/operations", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> operations(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        var caller = currentUser.require();
        return audit.operations(new PortalDto.AuditFilter(from, to, null, null, null), caller);
    }
}
