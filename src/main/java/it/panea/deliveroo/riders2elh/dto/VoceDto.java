package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VoceDto(
        @JsonProperty("id_voce") @NotBlank String idVoce,
        @NotBlank String descrizione,
        @JsonProperty("mese_riferimento_richiesto") @NotNull Boolean meseRiferimentoRichiesto
) {}
