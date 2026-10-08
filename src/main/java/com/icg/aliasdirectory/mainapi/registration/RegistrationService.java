package com.icg.aliasdirectory.mainapi.registration;

import com.icg.aliasdirectory.mainapi.availability.BlindIndex;
import com.icg.aliasdirectory.mainapi.availability.Normalizer;
import com.icg.aliasdirectory.mainapi.availability.Reason;
import com.icg.aliasdirectory.mainapi.availability.RegistryRepository;
import com.icg.aliasdirectory.mainapi.availability.AvailabilityRule;
import com.icg.aliasdirectory.messaging.icg.schema.Document;
import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Servicio F2: registra un alias (PrxyRegn → PrxyRegnRpt).
 *
 * <p>Es el primer punto del sistema que <strong>cifra</strong>. Todo lo anterior
 * —disponibilidad de registro y de resolución— sólo lee y compara índices ciegos;
 * aquí entran datos nuevos al padrón y hay que protegerlos al escribirlos.
 *
 * <p>El orden de las operaciones no es arbitrario:
 *
 * <ol>
 *   <li><b>Normalizar.</b> Antes que nada: la huella y el cifrado tienen que
 *       salir del mismo valor normalizado, o el alias que se guarda y el que se
 *       busca dejan de coincidir.</li>
 *   <li><b>Decidir.</b> Se calculan las huellas y se consulta si el alias está
 *       libre. Va antes de cifrar para no gastar cinco llamadas al KMS en un alta
 *       que va a terminar rechazada.</li>
 *   <li><b>Cifrar y persistir</b>, en una sola transacción.</li>
 * </ol>
 *
 * <p>La decisión reutiliza {@link AvailabilityRule}, la misma que atiende
 * F1. No es ahorro de código: el banco tiene que poder preguntar «¿está libre?»
 * y recibir la misma respuesta que recibirá al intentar registrarlo. Dos
 * implementaciones de la misma regla divergen, y el día que diverjan el banco
 * verá «disponible» y a continuación un rechazo.
 */
@Service
// Existe solo con el KMS encendido, y es una dependencia dura, no una
// comodidad: el registro CIFRA. Sin KMS no hay donde cifrar, y la
// alternativa —una llave en configuracion, como la que todavia usa el
// indice ciego en desarrollo— seria guardar el padron entero protegido
// por un secreto que vive en un archivo de texto. Mejor no ofrecer el
// servicio que ofrecerlo mal: sin KMS, el consumidor responde 501.
@ConditionalOnProperty(name = "icg.kms.enabled", havingValue = "true")
public class RegistrationService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationService.class);

    /** v1.0 sólo admite alias telefónico, que viaja como SHID (H-01). */
    public static final String ALIAS_TYPE = "SHID";

    private final MessageSerializer serializer;
    private final BlindIndex index;
    private final RegistryRepository registry;
    private final RegistryWriteRepository writeRepository;
    private final AliasRegistrar registrar;
    private final ResponseBuilder responseBuilder;

    public RegistrationService(MessageSerializer serializer, BlindIndex index,
            RegistryRepository registry, RegistryWriteRepository writeRepository,
            AliasRegistrar registrar, ResponseBuilder responseBuilder) {
        this.serializer = serializer;
        this.index = index;
        this.registry = registry;
        this.writeRepository = writeRepository;
        this.registrar = registrar;
        this.responseBuilder = responseBuilder;
    }

    /**
     * @return el PrxyRegnRpt y el código HTTP con el que debe viajar: 201 si el
     *         alias quedó registrado, 409 si estaba tomado. El código lo decide
     *         {@code ResponseBuilder} junto con el cuerpo, para que no
     *         puedan contradecirse.
     */
    public ResponseBuilder.Response handle(byte[] requestBody, String registeringBic) {
        var request = serializer.parse(requestBody, Document.class).getPrxyRegn();
        var data = RegistrationExtractor.from(request);

        int bankId = writeRepository.bankId(registeringBic).orElseThrow(
                () -> new IllegalStateException("El BIC " + registeringBic + " no está en"
                        + " participant_bank. El borde lo dejó pasar y no debería."));

        String alias = Normalizer.phone(data.alias());
        String dpi = Normalizer.dpi(data.dpi());
        String iban = Normalizer.iban(data.iban());

        byte[] aliasBidx = index.ofAlias(alias);
        byte[] dpiBidx = index.ofDpi(dpi);
        byte[] ibanBidx = index.ofIban(iban);
        byte[] customerIdBidx = index.ofCustomerId(
                Normalizer.customerId(data.customerId()));

        var actives = registry.activeRegistrations(ALIAS_TYPE, aliasBidx, dpiBidx);
        var decision = AvailabilityRule.decide(actives, registeringBic);

        // Lo que rechaza el alta es el MOTIVO, no Vrfctn.
        //
        // Los dos campos no dicen lo mismo, y confundirlos rompía el multibanco:
        // Vrfctn responde «¿existe un registro ACTIVO de este alias?» y Rsn sólo
        // aparece cuando hay un impedimento para que ESTE banco lo registre. El
        // caso multibanco normal —el alias ya activo en otra entidad, a nombre
        // del MISMO DPI— devuelve Vrfctn=true SIN motivo justamente porque este
        // banco sí puede registrarlo; es lo que dice AvailabilityRule en su
        // javadoc y lo que el directorio existe para permitir (F3 devuelve una
        // lista multibanco).
        //
        // Rechazando por Vrfctn, ese alta salía con un 409 sin Rsn y el alias
        // quedaba atrapado en la primera entidad que lo registrara.
        if (decision.reason().isPresent()) {
            log.info("registro rechazado: banco={} msgId={} motivo={}",
                    registeringBic, data.msgIdOrigen(), decision.reason().get());
            return responseBuilder.rejected(request, decision.reason().get(),
                    decision.reasonBic().orElse(null));
        }

        // Un cliente sostiene un solo alias vigente en cada banco (V1.1.5). Va
        // antes que la comprobación de cuenta porque es la más estricta: el caso
        // que de verdad se va a dar es éste, y así el log nombra el impedimento
        // real en vez de uno que también se cumple de rebote.
        var clienteOcupado = writeRepository.customerActiveRegistration(
                bankId, customerIdBidx, dpiBidx);
        if (clienteOcupado.isPresent()) {
            log.info("registro rechazado: banco={} msgId={} motivo={} causa=cliente regnId={}",
                    registeringBic, data.msgIdOrigen(),
                    Reason.ACTIVE_SAME_ACCOUNT, clienteOcupado.get());
            return responseBuilder.rejected(request, Reason.ACTIVE_SAME_ACCOUNT, null);
        }

        // Una cuenta sostiene un solo alias vigente (V1.1.4). No lo decide
        // AvailabilityRule a propósito: esa regla razona sobre los registros DEL
        // ALIAS, y la cuenta no aparece en ellos. Son dos preguntas distintas y
        // mezclarlas obligaría a pasarle a la regla un dato que no usa para nada
        // más.
        //
        // Va después del rechazo por alias tomado porque ese es el impedimento
        // que el Anexo nombra y el que el banco espera primero. Y va antes de
        // registrar() para que un alta rechazada no deje sembrados el titular, el
        // alias ni la cuenta: el rollback los quitaría igual, pero gastando las
        // llamadas de cifrado al KMS.
        var ocupante = writeRepository.accountActiveRegistration(bankId, ibanBidx);
        if (ocupante.isPresent()) {
            log.info("registro rechazado: banco={} msgId={} motivo={} causa=cuenta regnId={}",
                    registeringBic, data.msgIdOrigen(),
                    Reason.ACTIVE_SAME_ACCOUNT, ocupante.get());
            // Sin BIC en AddtlInf: la cuenta es del banco que pregunta, así que no
            // hay otra entidad que nombrar.
            return responseBuilder.rejected(request, Reason.ACTIVE_SAME_ACCOUNT, null);
        }

        var registrado = registrar.register(data, alias, dpi, iban,
                aliasBidx, dpiBidx, ibanBidx, customerIdBidx, bankId);

        // Se registra el RegnId y el MsgId, que son identificadores del trámite, y
        // nunca el alias ni el DPI (regla T-8). Con el RegnId se rastrea todo lo
        // demás en la tabla, que es donde ese dato sí puede estar.
        log.info("registro aceptado: banco={} msgId={} regnId={}",
                registeringBic, data.msgIdOrigen(), registrado.regnId());
        return responseBuilder.accepted(request, registrado);
    }
}
