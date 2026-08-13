package it.panea.deliveroo.riders2elh.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RettificaVoceRequest(@NotNull @Valid VoceDto dati, @NotBlank String motivo) {}
