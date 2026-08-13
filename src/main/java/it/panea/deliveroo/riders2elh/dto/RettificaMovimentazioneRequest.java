package it.panea.deliveroo.riders2elh.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RettificaMovimentazioneRequest(@NotNull @Valid MovimentazioneDto dati, @NotBlank String motivo) {}
