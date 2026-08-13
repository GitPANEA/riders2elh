package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

public record MovimentazioneDto(
        @JsonProperty("id_movimentazione") @NotBlank String idMovimentazione,
        @JsonProperty("id_rider") @NotBlank String idRider,
        @JsonProperty("periodo_da") @NotNull LocalDate periodoDa,
        @JsonProperty("periodo_a") @NotNull LocalDate periodoA,
        @NotEmpty @Valid List<ConsegnaDto> consegne,
        @JsonProperty("totali_consegne") @NotNull @Valid TotaliConsegneDto totaliConsegne,
        @JsonProperty("modifiche_integrazioni") @Valid List<VoceMovimentazioneDto> modificheIntegrazioni,
        @JsonProperty("totali_modifiche_integrazioni") @Valid TotaliVoceDto totaliModificheIntegrazioni,
        @JsonProperty("prospetto_finale") @NotEmpty @Valid List<VoceMovimentazioneDto> prospettoFinale,
        @NotNull @Valid RiepilogoDto riepilogo
) {}
