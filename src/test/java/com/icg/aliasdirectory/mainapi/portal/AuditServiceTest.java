package com.icg.aliasdirectory.mainapi.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * La regla T-8 sobre la bitácora: ninguna línea lleva alias, cuenta ni DPI en
 * claro (Anexo §5.2 y §8.4).
 *
 * <p>Esta prueba existe porque {@link AuditService} es el único lugar donde esa
 * regla se puede hacer cumplir. {@code portal_audit_log} es una tabla inmutable
 * y particionada: un valor sensible escrito ahí no se corrige editando una fila,
 * y el motor no tiene forma de verificar el contenido de un JSON. Sin esta
 * prueba, el control es una línea de código que nadie vuelve a mirar.
 *
 * <p>El repositorio se reemplaza por uno que acumula en memoria. No hace falta
 * una base: lo que se verifica es qué se le pide escribir, no que la escritura
 * funcione.
 */
class AuditServiceTest {

    private RecordingRepository repository;
    private AuditService service;
    private PortalUser user;

    @BeforeEach
    void setUp() {
        repository = new RecordingRepository();
        service = new AuditService(repository);
        user = new PortalUser(1L, 14, "GTCOGTGC", "agente.cc", "Agente de Call Center",
                Set.of(PortalPermission.QUERY_BY_ALIAS));
    }

    @Test
    @DisplayName("un detalle ya enmascarado se escribe tal cual")
    void writesMaskedDetail() {
        service.record(AuditEntry.ok(AuditOperation.ALIAS_QUERY, "ALIAS", "a1b2c3",
                Map.of("criterio", "ALIAS", "cuenta", "****1111", "registros", "1")),
                user, null);

        assertThat(repository.rows).hasSize(1);
        var row = repository.rows.getFirst();
        assertThat(row.detailJson()).contains("****1111");
        assertThat(row.userLogin()).isEqualTo("agente.cc");
        assertThat(row.bankBicfi()).isEqualTo("GTCOGTGC");
    }

    @Test
    @DisplayName("una cuenta completa se rechaza y no se escribe nada")
    void refusesAnUnmaskedAccount() {
        assertThatThrownBy(() -> service.record(
                AuditEntry.ok(AuditOperation.ALIAS_QUERY, "ALIAS", "a1b2c3",
                        Map.of("cuenta", "001234567891111")),
                user, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("T-8");

        // Lo importante no es que falle, sino que no quede media línea escrita.
        assertThat(repository.rows).isEmpty();
    }

    @Test
    @DisplayName("un DPI completo se rechaza")
    void refusesANationalId() {
        assertThatThrownBy(() -> service.record(
                AuditEntry.ok(AuditOperation.ALIAS_QUERY, "ALIAS", null,
                        Map.of("titular", "2547891230101")),
                user, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("un IBAN se rechaza aunque no sea una ristra de digitos")
    void refusesAnIban() {
        assertThatThrownBy(() -> service.record(
                AuditEntry.ok(AuditOperation.CANCEL_REQUEST, "ALIAS", null,
                        Map.of("cuenta", "GT82TRAJ01020000001210029690")),
                user, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("un telefono usado como alias se rechaza")
    void refusesAPhoneAlias() {
        assertThatThrownBy(() -> service.record(
                AuditEntry.ok(AuditOperation.ALIAS_QUERY, "ALIAS", null,
                        Map.of("alias", "+50244444444")),
                user, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("los valores cortos y no sensibles pasan sin estorbar")
    void allowsOrdinaryValues() {
        service.record(AuditEntry.ok(AuditOperation.USER_STATUS, "USUARIO", "7",
                Map.of("usuario", "consulta.cc", "estado", "INACTIVO",
                        "motivo", "Traslado de area")), user, null);

        // Un identificador de registro, un conteo o una fecha no son datos del
        // titular; si la regla los bloqueara, la bitacora quedaria sin contexto
        // util y alguien terminaria apagandola.
        service.record(AuditEntry.ok(AuditOperation.AUDIT_QUERY, "BITACORA", null,
                Map.of("desde", "2026-10-01", "hasta", "2026-10-05", "filas", "42")),
                user, null);

        assertThat(repository.rows).hasSize(2);
    }

    @Test
    @DisplayName("sin detalle la linea se escribe igual")
    void writesWithoutDetail() {
        service.record(AuditEntry.ok(AuditOperation.CANCEL_APPROVE, "SOLICITUD", "9"),
                user, null);

        assertThat(repository.rows).hasSize(1);
        assertThat(repository.rows.getFirst().detailJson()).isNull();
    }

    @Test
    @DisplayName("sin peticion HTTP la IP queda en un valor valido, no en null")
    void fallsBackToAValidIp() {
        service.record(AuditEntry.ok(AuditOperation.ALIAS_QUERY, "ALIAS", "a1b2c3"),
                user, null);

        // La columna es NOT NULL y se escribe con INET6_ATON, que devuelve NULL
        // ante cualquier texto que no sea una IP. Un valor invalido aqui haria
        // fallar el INSERT y, como la bitacora falla cerrado, la operacion.
        assertThat(repository.rows.getFirst().ip()).isEqualTo("0.0.0.0");
    }

    /** Repositorio que acumula en memoria en vez de tocar la base. */
    private static final class RecordingRepository extends AuditRepository {
        private final List<AuditRow> rows = new ArrayList<>();

        private RecordingRepository() {
            super(null);
        }

        @Override
        public void write(AuditRow row) {
            rows.add(row);
        }
    }
}
