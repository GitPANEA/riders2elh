package it.panea.deliveroo.riderpay.config.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.UUID;

/** Costruisce e firma i JWT emessi da {@code /oauth2/token} (§ flusso client_credentials). */
@Service
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final String issuer;

    public JwtTokenService(RSAPublicKey publicKey, RSAPrivateKey privateKey,
                            @Value("${riderpay.security.jwt.issuer}") String issuer) {
        JWK jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(UUID.randomUUID().toString()).build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new com.nimbusds.jose.jwk.JWKSet(jwk));
        this.jwtEncoder = new NimbusJwtEncoder(jwkSource);
        this.issuer = issuer;
    }

    public String emettiToken(String clientId, String scope, long ttlSecondi) {
        Instant ora = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(clientId)
                .issuedAt(ora)
                .expiresAt(ora.plusSeconds(ttlSecondi))
                .id(UUID.randomUUID().toString())
                .claim("scope", scope)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
