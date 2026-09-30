package com.icg.aliasdirectory.mainapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los siete campos del Anexo §5.1, comprobados contra el esquema real.
 *
 * <p>El §5.1 es una tabla en un documento Word. Esta prueba la convierte en algo
 * que falla si alguien la contradice: cada uno de los siete campos existe como
 * columna binaria —o sea, cifrada— y ninguno tiene una columna gemela en texto
 * claro por la que se pudiera consultar sin descifrar.
 *
 * <p>La segunda mitad es igual de importante y menos obvia: los campos que NO
 * llevan índice ciego tampoco deben tenerlo. Un índice ciego sobre un campo de
 * dos valores —tipo de cuenta, moneda— no oculta nada: hay dos HMAC distintos en
 * toda la tabla y contar cuál aparece más resuelve cuál es cuál sin tocar la
 * llave. Agregarle un {@code _bidx} a esas columnas sería un retroceso que nadie
 * notaría revisando el diff de una migración.
 */
class EncryptedFieldCatalogTest extends MySqlBackedTest {

    /** Los siete del §5.1: tabla.columna cifrada → columna de índice ciego, o null si no lleva. */
    private static final Map<String, String> CAMPOS_DEL_ANEXO = new LinkedHashMap<>();

    static {
        // Alta cardinalidad: se buscan por igualdad, llevan índice ciego.
        CAMPOS_DEL_ANEXO.put("alias.value_enc", "value_bidx");
        CAMPOS_DEL_ANEXO.put("account.iban_enc", "iban_bidx");
        CAMPOS_DEL_ANEXO.put("holder.dpi_enc", "dpi_bidx");
        CAMPOS_DEL_ANEXO.put("holder_bank.customer_id_enc", "customer_id_bidx");
        // Baja cardinalidad o sin búsqueda: cifrados, SIN índice ciego.
        CAMPOS_DEL_ANEXO.put("account.acct_type_enc", null);
        CAMPOS_DEL_ANEXO.put("account.currency_enc", null);
        CAMPOS_DEL_ANEXO.put("resolution_event.requesting_bank_enc", null);
    }

    private static final String COLUMN_TYPE = """
            SELECT data_type FROM information_schema.columns
             WHERE table_schema = ? AND table_name = ? AND column_name = ?
            """;

    private static String tipoDe(String tabla, String column) throws SQLException {
        try (Connection c = IntegrationMySql.connectAs(
                IntegrationMySql.ROOT_USER, IntegrationMySql.ROOT_PASSWORD);
                PreparedStatement ps = c.prepareStatement(COLUMN_TYPE)) {
            ps.setString(1, IntegrationMySql.BASE);
            ps.setString(2, tabla);
            ps.setString(3, column);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    @DisplayName("los siete campos del §5.1 están como columna binaria, es decir cifrados")
    void losSieteCamposEstanCifrados() throws SQLException {
        for (String campo : CAMPOS_DEL_ANEXO.keySet()) {
            String[] partes = campo.split("\\.");
            assertThat(tipoDe(partes[0], partes[1]))
                    .as("%s debe existir y ser varbinary (Anexo §5.1)", campo)
                    .isEqualTo("varbinary");
        }
    }

    @Test
    @DisplayName("los campos de alta cardinalidad tienen su índice ciego de 32 bytes")
    void losIndicesCiegosExisten() throws SQLException {
        for (var e : CAMPOS_DEL_ANEXO.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            String tabla = e.getKey().split("\\.")[0];
            assertThat(tipoDe(tabla, e.getValue()))
                    .as("%s.%s es el índice ciego de %s", tabla, e.getValue(), e.getKey())
                    .isEqualTo("binary");
        }
    }

    @Test
    @DisplayName("tipo de cuenta, moneda y BIC consultante NO llevan índice ciego")
    void lowCardinalityOnesDoNotCarryIt() throws SQLException {
        // Con dos valores posibles, un índice ciego es un análisis de frecuencias
        // resuelto: dos HMAC distintos en toda la tabla, y el más repetido es el
        // más común. No protege, y da la impresión de que sí.
        for (var e : CAMPOS_DEL_ANEXO.entrySet()) {
            if (e.getValue() != null) {
                continue;
            }
            String[] partes = e.getKey().split("\\.");
            String candidato = partes[1].replace("_enc", "_bidx");
            assertThat(tipoDe(partes[0], candidato))
                    .as("%s.%s no debería existir: es un campo de baja cardinalidad",
                            partes[0], candidato)
                    .isNull();
        }
    }

    @Test
    @DisplayName("ningún campo del §5.1 tiene una columna gemela en texto claro")
    void sinColumnaEnClaro() throws SQLException {
        // El riesgo real no es que alguien borre el cifrado, es que agregue al
        // lado una columna «para poder buscar» y nadie lo note.
        for (String campo : CAMPOS_DEL_ANEXO.keySet()) {
            String[] partes = campo.split("\\.");
            String enClaro = partes[1].replace("_enc", "");
            assertThat(tipoDe(partes[0], enClaro))
                    .as("%s.%s en claro haría inútil el cifrado de al lado", partes[0], enClaro)
                    .isNull();
        }
    }
}
