package it.panea.deliveroo.riders2elh.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configurazione OpenAPI 3 / Swagger UI.
 * <p>
 * Dichiara due schemi di sicurezza alternativi, entrambi utilizzabili dal pulsante
 * "Authorize" della UI:
 * <ul>
 *   <li><b>oauth2</b> — flusso {@code client_credentials}: si inseriscono client id e secret
 *       e la UI chiama da sé {@code POST /oauth2/token}, poi allega il Bearer alle richieste.
 *       È la via comoda per provare gli endpoint dal browser.</li>
 *   <li><b>bearerAuth</b> — si incolla a mano un token già ottenuto altrove (curl, Postman).
 *       Serve come ripiego: il flusso automatico può non funzionare se il browser blocca la
 *       chiamata al token endpoint per il certificato TLS self-signed non accettato.</li>
 * </ul>
 * Senza uno schema di sicurezza dichiarato qui, ogni "Try it out" su {@code /api/v1/**}
 * tornerebbe {@code 401}: la UI non avrebbe modo di allegare il token.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Context path dell'applicazione, necessario per comporre il {@code tokenUrl} dello schema
     * oauth2: un path assoluto come {@code /oauth2/token} punterebbe alla root del server,
     * fuori dall'applicazione, e il pulsante "Authorize" fallirebbe con un 404.
     */
    private final String contextPath;

    public OpenApiConfig(@Value("${server.servlet.context-path:}") String contextPath) {
        this.contextPath = contextPath;
    }

    @Bean
    public OpenAPI openApi() {
        SecurityScheme oauth2 = new SecurityScheme()
                .type(SecurityScheme.Type.OAUTH2)
                .description("""
                        Flusso client_credentials. La UI richiede il token a POST /oauth2/token \
                        con le credenziali inserite nel dialogo, e lo allega come Bearer.

                        Nota: RsaKeyProvider rigenera la coppia RSA a ogni avvio \
                        dell'applicazione, quindi dopo ogni deploy/restart va rifatta \
                        l'autorizzazione — i token emessi prima non sono piu verificabili.""")
                .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                        .tokenUrl(contextPath + "/oauth2/token")
                        .scopes(new io.swagger.v3.oas.models.security.Scopes())));

        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Token JWT ottenuto da POST /oauth2/token, incollato a mano "
                        + "(senza il prefisso 'Bearer ').");

        return new OpenAPI()
                .info(new Info()
                        .title("riders2eLH — API di ingestione pagamenti rider")
                        .version("0.1.0")
                        .description("""
                                Ingestione e consultazione dei dati di pagamento dei rider su Oracle, \
                                con modello append-only storicizzato (SCD Type 2): nessun UPDATE/DELETE \
                                fisico sui dati di business, ogni nuovo arrivo genera una nuova versione \
                                datata e gli annullamenti sono anch'essi insert.

                                Tutti gli endpoint /api/v1/** richiedono un access token \
                                (OAuth2 client_credentials su POST /oauth2/token).

                                Codici di errore ricorrenti: 401 = problema di token, \
                                403 = problema della richiesta (tipicamente Content-Type), \
                                404 = risorsa non trovata, 409 = conflitto di concorrenza, \
                                500 = errore interno (la causa e nel log del server)."""))
                .components(new Components()
                        .addSecuritySchemes("oauth2", oauth2)
                        .addSecuritySchemes("bearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("oauth2").addList("bearerAuth"));
    }
}
