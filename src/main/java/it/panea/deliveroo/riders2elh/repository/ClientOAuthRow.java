package it.panea.deliveroo.riders2elh.repository;

public record ClientOAuthRow(
        long idClientOauth,
        String clientId,
        String clientSecretHash,
        String scopeConcessi,
        long tokenTtlSecondi,
        boolean attivo
) {}
