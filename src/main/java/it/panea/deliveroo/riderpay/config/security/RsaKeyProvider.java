package it.panea.deliveroo.riderpay.config.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;

/**
 * Genera una coppia di chiavi RSA all'avvio, usata per firmare/verificare i JWT
 * emessi da {@code /oauth2/token}. Chiavi solo in memoria: un riavvio invalida
 * i token gia emessi (accettabile per una singola istanza in ambiente di test).
 */
@Configuration
public class RsaKeyProvider {

    private final KeyPair keyPair = generaCoppiaChiavi();

    @Bean
    public RSAPublicKey rsaPublicKey() {
        return (RSAPublicKey) keyPair.getPublic();
    }

    @Bean
    public RSAPrivateKey rsaPrivateKey() {
        return (RSAPrivateKey) keyPair.getPrivate();
    }

    private static KeyPair generaCoppiaChiavi() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Algoritmo RSA non disponibile nella JVM", e);
        }
    }
}
