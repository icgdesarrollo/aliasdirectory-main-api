package com.icg.aliasdirectory.mainapi.cancellation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icg.aliasdirectory.messaging.serialization.MessageSerializer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

/**
 * La autorización elevada del alcance ALL_BANKS (Anexo F5).
 *
 * <p>Existe porque faltaba y nadie lo notó hasta ejecutarlo: el 05/10/2026, en
 * el ambiente de desarrollo, GTCOGTGC dio de baja un alias de AGROGTGC y el
 * sistema lo reportó como exitoso. {@code participant_bank.allows_all_banks}
 * estaba —y está— en 0 para las veinte entidades, pero ninguna consulta la leía.
 *
 * <p>Un banco que puede borrar los alias de los demás es un problema de otra
 * magnitud que un error de validación, así que esta prueba fija la guarda. Lo
 * que verifica es que el rechazo ocurre ANTES de resolver el alias: las demás
 * dependencias del servicio son nulas a propósito, de modo que si alguien mueve
 * la guarda más abajo, la prueba revienta con un NullPointerException en vez de
 * pasar silenciosamente.
 */
class CancellationScopeTest {

    private static final String ALL_BANKS_REQUEST = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Document xmlns="urn:icg:directorio.alias:icg.001">
              <PrxyCxl>
                <Assgnmt>
                  <MsgId>PRXCXL-TEST-0001</MsgId>
                  <CreDtTm>2026-10-05T18:25:00Z</CreDtTm>
                  <Assgnr><Agt><FinInstnId><BICFI>GTCOGTGC</BICFI></FinInstnId></Agt></Assgnr>
                  <Assgne><Agt><FinInstnId><BICFI>ICGSGTGC</BICFI></FinInstnId></Agt></Assgne>
                </Assgnmt>
                <Prxy><Tp><Cd>SHID</Cd></Tp><Id>+50255555555</Id></Prxy>
                <CxlScp>ALL_BANKS</CxlScp>
              </PrxyCxl>
            </Document>
            """;

    @Test
    @DisplayName("un banco sin autorizacion no puede dar de baja alias de otras entidades")
    void refusesAllBanksWithoutElevatedAuthorisation() {
        var service = serviceWith(new ScopeRepository(false));

        assertThatThrownBy(() -> service.handle(
                ALL_BANKS_REQUEST.getBytes(StandardCharsets.UTF_8), "GTCOGTGC"))
                .isInstanceOf(CancellationService.ScopeNotAuthorizedException.class)
                .hasMessageContaining("GTCOGTGC")
                .hasMessageContaining("ALL_BANKS");
    }

    @Test
    @DisplayName("con autorizacion la guarda deja pasar y la baja sigue su curso")
    void letsAnAuthorisedBankThrough() {
        var service = serviceWith(new ScopeRepository(true));

        // Pasada la guarda, el servicio sigue adelante y se topa con las
        // dependencias nulas. Que falle AQUI y no con ScopeNotAuthorizedException
        // es justamente lo que se comprueba: la autorizacion no bloqueo al banco
        // que si la tiene.
        assertThatThrownBy(() -> service.handle(
                ALL_BANKS_REQUEST.getBytes(StandardCharsets.UTF_8), "GTCOGTGC"))
                .isNotInstanceOf(CancellationService.ScopeNotAuthorizedException.class);
    }

    private CancellationService serviceWith(ScopeRepository repository) {
        // Todo lo demas es nulo: el camino bajo prueba termina en la guarda.
        return new CancellationService(new MessageSerializer(), null, repository,
                new FixedBankRegistry(), null, "ICGSGTGC");
    }

    /** Contesta la autorizacion y nada mas. */
    private static final class ScopeRepository extends CancellationRepository {
        private final boolean allowed;

        private ScopeRepository(boolean allowed) {
            super(null);
            this.allowed = allowed;
        }

        @Override
        public boolean allowsAllBanks(int bankId) {
            return allowed;
        }
    }

    /** Resuelve el BIC a un id sin tocar la base. */
    private static final class FixedBankRegistry
            extends com.icg.aliasdirectory.mainapi.registration.RegistryWriteRepository {

        private FixedBankRegistry() {
            super(null);
        }

        @Override
        public java.util.Optional<Integer> bankId(String bic) {
            return java.util.Optional.of(14);
        }
    }
}
