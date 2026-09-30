package com.icg.aliasdirectory.mainapi.portal;

/** Las solicitudes del portal que no se pueden atender, con su código HTTP. */
public class PortalException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public PortalException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public static PortalException notFound(String message) {
        return new PortalException(404, "ICG-404-SOLICITUD-INEXISTENTE", message);
    }

    public static PortalException conflict(String code, String message) {
        return new PortalException(409, code, message);
    }

    public static PortalException badRequest(String message) {
        return new PortalException(400, "ICG-400-SOLICITUD-INVALIDA", message);
    }

    public static PortalException forbidden(String message) {
        return new PortalException(403, "ICG-403-SIN-PERMISO", message);
    }
}
