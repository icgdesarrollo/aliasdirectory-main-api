package com.icg.aliasdirectory.mainapi.portal;

import java.util.Map;

/**
 * Una línea de la bitácora, antes de escribirse.
 *
 * <p><b>Nada de lo que entra aquí puede ser un alias, un IBAN o un IdCliente en
 * claro</b> (regla T-8, Anexo §5.2). {@code entityRef} lleva el identificador no
 * reversible del registro —el id del padrón— y {@code detail} sólo admite
 * valores ya enmascarados. El motor no puede verificarlo; lo verifica
 * {@link AuditService}, que rechaza la escritura si detecta un valor largo con
 * pinta de dato sensible.
 *
 * @param operation  una constante de {@link AuditOperation}
 * @param entity     tipo de objeto afectado: ALIAS, USUARIO, ROL, SOLICITUD
 * @param entityRef  identificador NO sensible del objeto
 * @param detail     pares ya enmascarados; null si no hay nada que añadir
 */
public record AuditEntry(String operation, AuditResult result, String entity, String entityRef,
        Integer httpStatus, Map<String, String> detail) {

    public static AuditEntry ok(String operation, String entity, String entityRef) {
        return new AuditEntry(operation, AuditResult.EXITO, entity, entityRef, 200, null);
    }

    public static AuditEntry ok(String operation, String entity, String entityRef,
            Map<String, String> detail) {
        return new AuditEntry(operation, AuditResult.EXITO, entity, entityRef, 200, detail);
    }

    public static AuditEntry rejected(String operation, String entity, String entityRef,
            String motivo) {
        return new AuditEntry(operation, AuditResult.RECHAZO, entity, entityRef, 403,
                Map.of("motivo", motivo));
    }
}
