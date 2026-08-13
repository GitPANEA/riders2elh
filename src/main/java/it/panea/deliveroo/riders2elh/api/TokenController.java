package it.panea.deliveroo.riders2elh.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.panea.deliveroo.riders2elh.common.ClientNonAutorizzatoException;
import it.panea.deliveroo.riders2elh.dto.ErroreResponse;
import it.panea.deliveroo.riders2elh.config.security.JwtTokenService;
import it.panea.deliveroo.riders2elh.dto.TokenResponse;
import it.panea.deliveroo.riders2elh.repository.ClientOAuthRow;
import it.panea.deliveroo.riders2elh.service.ClientAuthenticationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Endpoint dell'authorization server: grant type client_credentials (§ flusso OAuth2). */
@Tag(name = "Autenticazione", description = "Emissione dell'access token (OAuth2 client_credentials)")
@RestController
public class TokenController {

    private final ClientAuthenticationService authenticationService;
    private final JwtTokenService jwtTokenService;

    public TokenController(ClientAuthenticationService authenticationService, JwtTokenService jwtTokenService) {
        this.authenticationService = authenticationService;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * POST /oauth2/token — client_id/client_secret via Basic Auth (standard OAuth2)
     * o via parametri del body form-urlencoded (compatibilita con Postman).
     */
    @Operation(summary = "Emissione dell'access token",
            description = """
                    Grant type supportato: **client_credentials** (solo machine-to-machine).

                    Le credenziali si possono passare in due modi: header Basic Auth (standard \
                    OAuth2) oppure client_id/client_secret nel body form-urlencoded. Il \
                    Content-Type deve essere application/x-www-form-urlencoded.

                    Il token restituito è un JWT RS256 con scadenza definita per client \
                    (TOKEN_TTL_SECONDI in T_CLIENT_OAUTH).

                    **Attenzione**: la coppia di chiavi RSA è generata in memoria a ogni avvio \
                    dell'applicazione e non è persistita. Ogni riavvio del servizio invalida \
                    tutti i token già emessi — dopo un deploy va richiesto un token nuovo.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Token emesso"),
            @ApiResponse(responseCode = "401", description = "Credenziali non valide, client non "
                    + "attivo, oppure grant_type non supportato",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "415", description = "Content-Type diverso da "
                    + "application/x-www-form-urlencoded",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @SecurityRequirements  // endpoint pubblico: nessun token richiesto per ottenere un token
    @PostMapping(value = "/oauth2/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<TokenResponse> emettiToken(
            @Parameter(description = "Deve valere 'client_credentials'", example = "client_credentials")
            @RequestParam("grant_type") String grantType,
            @Parameter(description = "Client id, se non passato via Basic Auth", example = "riders2elh-test")
            @RequestParam(value = "client_id", required = false) String clientIdBody,
            @Parameter(description = "Client secret, se non passato via Basic Auth")
            @RequestParam(value = "client_secret", required = false) String clientSecretBody,
            @Parameter(hidden = true)
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader) {

        if (!"client_credentials".equals(grantType)) {
            throw new ClientNonAutorizzatoException("grant_type non supportato: " + grantType);
        }

        String clientId = clientIdBody;
        String clientSecret = clientSecretBody;
        if (authorizationHeader != null && authorizationHeader.regionMatches(true, 0, "Basic ", 0, 6)) {
            String decoded = new String(Base64.getDecoder().decode(authorizationHeader.substring(6)), StandardCharsets.UTF_8);
            int separatore = decoded.indexOf(':');
            if (separatore > 0) {
                clientId = decoded.substring(0, separatore);
                clientSecret = decoded.substring(separatore + 1);
            }
        }
        if (clientId == null || clientSecret == null) {
            throw new ClientNonAutorizzatoException("client_id/client_secret mancanti");
        }

        ClientOAuthRow client = authenticationService.autentica(clientId, clientSecret);
        String accessToken = jwtTokenService.emettiToken(client.clientId(), client.scopeConcessi(), client.tokenTtlSecondi());

        return ResponseEntity.ok(new TokenResponse(accessToken, "Bearer", client.tokenTtlSecondi(), client.scopeConcessi()));
    }
}
