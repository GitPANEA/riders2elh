package it.panea.deliveroo.riderpay.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RettificaVoceRequest(@NotNull @Valid VoceDto dati, @NotBlank String motivo) {}
