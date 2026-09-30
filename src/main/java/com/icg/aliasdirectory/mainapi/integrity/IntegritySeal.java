package com.icg.aliasdirectory.mainapi.integrity;

import com.icg.aliasdirectory.mainapi.kms.TransitClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Calcula y verifica el sello de integridad de un registro (H-57).
 *
 * <p>Detecta que alguien con acceso directo a la base alteró una fila por fuera
 * de la aplicación. <strong>No lo impide</strong>: lo que lo impide es que esa
 * persona no tenga {@code UPDATE} sobre estas tablas. Esto es la red para cuando
 * alguien usa {@code root}, y {@code root} existe.
 *
 * <p>La llave es una tercera, {@code dir-alias-integridad}, distinta de la de
 * cifrado y de la del índice ciego. No es prolijidad: si el sello se calculara
 * con la llave del índice ciego, cualquiera que pudiera pedir índices ciegos
 * podría fabricar sellos válidos para una fila recién alterada.
 */
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
@Component
public class IntegritySeal {

    private final TransitClient kms;
    private final String key;

    public IntegritySeal(TransitClient kms,
            @Value("${icg.kms.integrity-key:dir-alias-integridad}") String key) {
        this.kms = kms;
        this.key = key;
    }

    /** @return el sello de esos campos y la versión de llave con que se calculó */
    public TransitClient.Fingerprint compute(SealedFields fields) {
        return kms.hmac(key, fields.canonica());
    }

    /**
     * Compara el sello guardado con el que corresponde a los datos actuales.
     *
     * <p>La comparación es en tiempo constante. Aquí no protege de gran cosa —el
     * atacante de este control tiene la base entera, no está midiendo tiempos—
     * pero comparar HMACs con {@code equals} es el tipo de costumbre que después
     * se copia a un sitio donde sí importa.
     */
    public Resultado verify(SealedFields fields, byte[] storedSeal) {
        if (storedSeal == null) {
            // Fila anterior a H-57. No se puede afirmar que esté íntegra ni que no
            // lo esté: nadie sabe qué le pasó antes de que existiera el control.
            // Decir «íntegra» aquí sería firmar algo que no consta.
            return Resultado.SIN_SELLO;
        }
        byte[] esperado = compute(fields).bytes();
        return MessageDigest.isEqual(esperado, storedSeal)
                ? Resultado.INTEGRA
                : Resultado.ALTERADA;
    }

    public enum Resultado {
        /** El sello coincide con los datos. */
        INTEGRA,
        /** El sello NO coincide: la fila cambió por fuera de la aplicación. */
        ALTERADA,
        /** Fila anterior al control; no hay sello contra el cual comparar. */
        SIN_SELLO
    }

    /**
     * Los campos que entran al sello, en el orden en que entran.
     *
     * <p>Incluye columnas de {@code alias}, {@code holder} y {@code account}
     * además de las del propio registro. Si el sello cubriera sólo
     * {@code account_id}, el DBA cambiaría {@code account.iban_enc} de la cuenta
     * ya referenciada y este registro seguiría verificando bien: el alias
     * apuntaría a otra cuenta sin que nada lo notara.
     *
     * @param aliasUuid   identidad de la fila: sin ella el sello de un registro
     *                    sirve para otro
     * @param regnId      identidad visible para el banco
     * @param bankId     quién es el dueño del registro
     * @param estado      ACTIVO/BLOQUEADO/INACTIVO: decide si el registro responde
     * @param nivelNombre cuánto del nombre se revela
     * @param aliasBidx   el alias que se busca
     * @param dpiBidx     de quién es
     * @param ibanEnc     a dónde apunta
     */
    public record SealedFields(
            String aliasUuid,
            String regnId,
            int bankId,
            String status,
            String nivelNombre,
            byte[] aliasBidx,
            byte[] dpiBidx,
            byte[] ibanEnc) {

        /**
         * Los campos en una sola cadena de bytes, sin ambigüedad posible.
         *
         * <p>Cada campo va precedido por su longitud en cuatro bytes. Concatenar a
         * secas sería un defecto: los campos {@code ("AB", "C")} y {@code ("A",
         * "BC")} producirían la misma cadena y por tanto el mismo sello, y eso es
         * una puerta para construir dos filas distintas con el mismo HMAC.
         *
         * <p>El orden es fijo y no puede cambiarse sin invalidar todos los sellos
         * existentes. Si alguna vez hay que agregar un campo, va al final y sube
         * la versión del esquema del sello.
         */
        byte[] canonica() {
            var out = new ByteArrayOutputStream();
            append(out, aliasUuid.getBytes(StandardCharsets.UTF_8));
            append(out, regnId.getBytes(StandardCharsets.UTF_8));
            append(out, Integer.toString(bankId).getBytes(StandardCharsets.UTF_8));
            append(out, status.getBytes(StandardCharsets.UTF_8));
            append(out, nivelNombre.getBytes(StandardCharsets.UTF_8));
            append(out, aliasBidx);
            append(out, dpiBidx);
            append(out, ibanEnc);
            return out.toByteArray();
        }

        private static void append(ByteArrayOutputStream out, byte[] value) {
            int n = value.length;
            out.write((n >>> 24) & 0xFF);
            out.write((n >>> 16) & 0xFF);
            out.write((n >>> 8) & 0xFF);
            out.write(n & 0xFF);
            out.write(value, 0, n);
        }
    }
}
