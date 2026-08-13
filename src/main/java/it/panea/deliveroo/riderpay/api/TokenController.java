package it.panea.deliveroo.riderpay.api;

import it.panea.deliveroo.riderpay.common.ClientNonAutorizzatoException;
import it.panea.deliveroo.riderpay.config.security.JwtTokenService;
import it.panea.deliveroo.riderpay.dto.TokenResponse;
import it.panea.deliveroo.riderpay.repository.ClientOAuthRow;
import it.panea.deliveroo.riderpay.service.ClientAuthenticationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Endpoint dell'authorization server: grant type client_credentials (§ flusso OAuth2). */
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
    @PostMapping(value = "/oauth2/token", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<TokenResponse> emettiToken(
            @RequestParam("grant_type") String grantType,
            @RequestParam(value = "client_id", required = false) String clientIdBody,
            @RequestParam(value = "client_secret", required = false) String clientSecretBody,
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
