package com.icg.aliasdirectory.mainapi.availability;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Lee del padrón lo justo para decidir disponibilidad.
 *
 * <p>Una sola consulta por alias, toda por índice ciego: nunca descifra. El
 * Anexo §5.1 prohíbe descifrar en SQL, y de todos modos no haría falta —lo único
 * que hay que saber es si el DPI coincide, y eso se compara HMAC contra HMAC.
 */
@Repository
public class RegistryRepository {

    /**
     * Registros vigentes del alias, con el DPI ya comparado en el motor.
     *
     * <p>Se filtra por ACTIVO y BLOQUEADO porque son los dos estados que ocupan
     * el cupo alias×banco; INACTIVO lo libera y por eso no cuenta. Es la misma
     * regla que materializa la columna generada {@code active_bank_id}.
     *
     * <p>El DPI se compara contra {@code holder.dpi_bidx} del titular del
     * registro. Que la comparación la haga el motor y no el servicio evita traer
     * el HMAC del titular a la aplicación, que es un dato que no necesita.
     */
    private static final String ACTIVE_REGISTRATIONS = """
            SELECT b.bicfi                                AS bic,
                   r.status = 'ACTIVO'                    AS activo,
                   h.dpi_bidx = :dpiBidx                  AS dpi_igual
              FROM alias a
              JOIN alias_registration r ON r.alias_id = a.id
              JOIN participant_bank   b ON b.id       = r.bank_id
              JOIN holder             h ON h.id       = r.holder_id
             WHERE a.type_cd    = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
            """;

    /**
     * Lo mismo, pero sin comparar DPI: Disponibilidad Resolución no lo recibe.
     *
     * <p>Es una consulta aparte y no la de arriba con un parámetro nulo. Un DPI
     * nulo compararía contra NULL, que en SQL no es falso sino desconocido, y la
     * columna {@code dpi_igual} saldría NULL —que JDBC entrega como false—. O
     * sea: funcionaría, devolvería «el DPI no coincide» para todos los registros,
     * y ese es exactamente el insumo del motivo que este método tiene prohibido
     * devolver. Mejor que el tipo no lo permita.
     */
    private static final String REGISTRATIONS_OF_ALIAS = """
            SELECT b.bicfi             AS bic,
                   r.status = 'ACTIVO' AS activo
              FROM alias a
              JOIN alias_registration r ON r.alias_id = a.id
              JOIN participant_bank   b ON b.id       = r.bank_id
             WHERE a.type_cd    = :tipo
               AND a.value_bidx = :aliasBidx
               AND r.status IN ('ACTIVO','BLOQUEADO')
            """;

    private final JdbcClient jdbc;

    public RegistryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<ActiveRegistration> activeRegistrations(String tipo, byte[] aliasBidx, byte[] dpiBidx) {
        return jdbc.sql(ACTIVE_REGISTRATIONS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .param("dpiBidx", dpiBidx)
                .query((rs, fila) -> new ActiveRegistration(
                        rs.getString("bic"),
                        rs.getBoolean("activo"),
                        rs.getBoolean("dpi_igual")))
                .list();
    }

    /** Registros vigentes del alias, sin comparar DPI (Disponibilidad Resolución). */
    public List<AliasRegistration> registrationsOfAlias(String tipo, byte[] aliasBidx) {
        return jdbc.sql(REGISTRATIONS_OF_ALIAS)
                .param("tipo", tipo)
                .param("aliasBidx", aliasBidx)
                .query((rs, fila) -> new AliasRegistration(
                        rs.getString("bic"),
                        rs.getBoolean("activo")))
                .list();
    }
}
