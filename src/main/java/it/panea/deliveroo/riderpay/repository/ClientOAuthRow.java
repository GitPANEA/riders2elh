package it.panea.deliveroo.riderpay.repository;

public record ClientOAuthRow(
        long idClientOauth,
        String clientId,
        String clientSecretHash,
        String scopeConcessi,
        long tokenTtlSecondi,
        boolean attivo
) {}
