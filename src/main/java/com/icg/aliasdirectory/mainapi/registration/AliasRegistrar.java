package com.icg.aliasdirectory.mainapi.registration;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
import com.icg.aliasdirectory.mainapi.integrity.IntegritySeal;
import com.icg.aliasdirectory.mainapi.kms.TransitClient;
import com.icg.aliasdirectory.mainapi.outbox.RegistryEventRepository;
import com.icg.aliasdirectory.mainapi.outbox.RegistryOperation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Cifra y escribe las seis filas de un alta, en una sola transacción.
 *
 * <p>Es una clase aparte de {@code RegistrationService} por un motivo mecánico, no
 * estético: {@code @Transactional} funciona con un proxy, y un método llamado
 * desde otro método de la <em>misma</em> clase no pasa por ese proxy. Escrito
 * todo junto, la anotación no habría hecho nada y las seis inserciones habrían
 * corrido cada una en su propia transacción implícita —con el agravante de que
 * todo parecería funcionar hasta el primer fallo a mitad de camino, que dejaría
 * un titular sin registro ocupando su DPI para siempre por la unicidad de
 * {@code dpi_bidx}.
 */
@Component
// Existe solo con el KMS encendido, y es una dependencia dura, no una
// comodidad: el registro CIFRA. Sin KMS no hay donde cifrar, y la
// alternativa —una llave en configuracion, como la que todavia usa el
// indice ciego en desarrollo— seria guardar el padron entero protegido
// por un secreto que vive en un archivo de texto. Mejor no ofrecer el
// servicio que ofrecerlo mal: sin KMS, el consumidor responde 501.
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
public class AliasRegistrar {

    private final BlindIndex index;
    private final RegistryWriteRepository writeRepository;
    private final TransitClient kms;
    private final IntegritySeal seal;
    private final RegistryEventRepository outbox;
    private final String encryptionKey;

    public AliasRegistrar(BlindIndex index, RegistryWriteRepository writeRepository,
            TransitClient kms, IntegritySeal seal, RegistryEventRepository outbox,
            @Value("${icg.kms.encryption-key}") String encryptionKey) {
        this.index = index;
        this.writeRepository = writeRepository;
        this.kms = kms;
        this.seal = seal;
        this.outbox = outbox;
        this.encryptionKey = encryptionKey;
    }

    /**
     * Da de alta el registro y devuelve lo necesario para la respuesta.
     *
     * <p>Recibe el alias, el DPI y el IBAN ya normalizados, y las cuatro huellas
     * ya calculadas: el servicio las necesitó antes para decidir si el alias
     * estaba libre y si el cliente y la cuenta estaban ocupados, y recalcularlas
     * aquí sería gastar cuatro llamadas más al KMS para obtener exactamente los
     * mismos bytes.
     *
     * <p>Las llamadas al KMS quedan dentro de la transacción. No es gratis —cada
     * cifrado es una ida por red con la transacción abierta— y se aceptó a
     * conciencia: cifrar antes de abrirla obligaría a cifrar también en las altas
     * que van a fallar por unicidad, y el volumen de altas es mucho menor que el
     * de resoluciones. Si alguna vez aparece contención de bloqueos, esto es lo
     * primero que hay que mirar.
     */
    @Transactional
    public Registrado register(RegistrationData data, String alias, String dpi,
            String iban, byte[] aliasBidx, byte[] dpiBidx, byte[] ibanBidx,
            byte[] customerIdBidx, int bankId) {

        int version = index.version();

        long holderId = writeRepository.upsertHolder(encrypt(dpi), dpiBidx, version);

        String customerId = Normalizer.customerId(data.customerId());
        long clienteBancoId = writeRepository.upsertCustomerBank(holderId, bankId,
                encrypt(customerId), customerIdBidx, version);

        long aliasId = writeRepository.upsertAlias(
                RegistrationService.ALIAS_TYPE, encrypt(alias), aliasBidx, version);

        long accountId = writeRepository.upsertAccount(bankId, encrypt(iban), ibanBidx,
                version, encrypt(data.accountType()), encrypt(data.currency()));

        long linkId = link(aliasId, holderId);

        String uuid = UUID.randomUUID().toString();
        Instant now = Instant.now();

        long registrationId = writeRepository.insertRegistration(uuid, aliasId, linkId, holderId,
                clienteBancoId, accountId, bankId, data.nivelNombre(), now,
                data.msgIdOrigen());
        String regnId = writeRepository.setRegnId(registrationId);

        // El sello va al final porque cubre el regn_id, que no existe hasta que el
        // motor asigna el AUTO_INCREMENT. Dentro de la misma transacción nadie ve
        // la fila sin sellar.
        //
        // El ibanEnc se vuelve a cifrar aquí y NO se reusa el de arriba: son bytes
        // distintos para el mismo IBAN —el cifrado es aleatorio— y el sello tiene
        // que cubrir lo que quedó EN LA BASE, no lo que se calculó en memoria. Por
        // eso se lee de la fila recién escrita.
        var fields = new IntegritySeal.SealedFields(
                uuid, regnId, bankId, "ACTIVO", data.nivelNombre(),
                aliasBidx, dpiBidx, writeRepository.accountIban(accountId, bankId));
        var huella = seal.compute(fields);
        writeRepository.seal(registrationId, huella.bytes(), huella.version());

        // La bandeja de salida, dentro de ESTA transacción (PTRAD-843). Publicar
        // a RabbitMQ aquí en vez de anotar la fila dejaría una ventana entre el
        // commit y el envío: si el proceso cae ahí, el alias queda registrado y
        // ningún shard se entera. El cliente no vería un error, vería que su
        // alias recién dado de alta «no existe».
        //
        // affects_routing en true siempre: un alta añade este banco al conjunto
        // de bancos del alias, y no puede ser un banco que ya estuviera —el único
        // uk_registration_customer_active lo impide—, así que el índice global de
        // ruteo cambia sin excepción.
        outbox.append(aliasId, bankId, registrationId, RegistryOperation.ALTA,
                true, data.msgIdOrigen());

        return new Registrado(uuid, regnId, now);
    }

    /**
     * Devuelve el vínculo vigente del alias, creándolo si no existe.
     *
     * <p>Aquí se materializa la regla «un alias, un DPI». Si el alias ya está
     * vinculado a otro titular, el alta no puede seguir. Lo impediría de todos
     * modos el índice único {@code uk_link_active}, pero como violación de
     * restricción, que le llega al banco como un 500 en vez de como el rechazo
     * que el protocolo define.
     *
     * <p>Que la regla de disponibilidad haya dicho «libre» y aun así se llegue a
     * este caso significa que alguien tomó el alias entre la consulta y el alta.
     * Es raro y es real: dos bancos pueden mandar la misma alta en el mismo
     * segundo.
     */
    private long link(long aliasId, long holderId) {
        var active = writeRepository.activeLink(aliasId);
        if (active.isPresent()) {
            if (active.get().holderId() != holderId) {
                throw new AliasTakenException(
                        "El alias quedó vinculado a otro titular entre la consulta y el alta");
            }
            return active.get().id();
        }
        return writeRepository.insertLink(aliasId, holderId);
    }

    /**
     * Cifra un valor y lo deja listo para una columna {@code VARBINARY}.
     *
     * <p>El ciphertext de Transit es texto ASCII —{@code vault:v1:<base64>}— y se
     * guardan sus bytes tal cual. Decodificar el base64 interno para almacenar
     * «sólo los datos» ahorraría unos bytes y perdería el prefijo de versión, que
     * es justamente lo que permite rotar la llave sin reescribir la tabla.
     */
    private byte[] encrypt(String plaintext) {
        return kms.encrypt(encryptionKey, plaintext).getBytes(StandardCharsets.US_ASCII);
    }

    /** Lo que hay que saber del alta recién hecha para armar la respuesta. */
    public record Registrado(String aliasUuid, String regnId, Instant registeredAt) {
    }

    /** El alias fue tomado por otro titular mientras se procesaba esta alta. */
    public static class AliasTakenException extends RuntimeException {
        public AliasTakenException(String message) {
            super(message);
        }
    }
}
