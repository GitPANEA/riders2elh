package it.panea.deliveroo.riders2elh.service;

import it.panea.deliveroo.riders2elh.common.ClientNonAutorizzatoException;
import it.panea.deliveroo.riders2elh.repository.ClientOAuthRepository;
import it.panea.deliveroo.riders2elh.repository.ClientOAuthRow;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class ClientAuthenticationService {

    private final ClientOAuthRepository repository;
    private final PasswordEncoder passwordEncoder;

    public ClientAuthenticationService(ClientOAuthRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Valida client_id/client_secret; ritorna il client autenticato o lancia 401. */
    public ClientOAuthRow autentica(String clientId, String clientSecret) {
        ClientOAuthRow client = repository.trovaPerClientId(clientId)
                .orElseThrow(() -> new ClientNonAutorizzatoException("Client non riconosciuto: " + clientId));
        if (!client.attivo()) {
            throw new ClientNonAutorizzatoException("Client disabilitato: " + clientId);
        }
        if (!passwordEncoder.matches(clientSecret, client.clientSecretHash())) {
            throw new ClientNonAutorizzatoException("Credenziali non valide per il client: " + clientId);
        }
        repository.aggiornaUltimoUtilizzo(clientId);
        return client;
    }
}
