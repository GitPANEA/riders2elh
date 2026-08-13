package it.panea.deliveroo.riders2elh.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RettificaAnagraficaRequest(@NotNull @Valid RiderAnagraficaDto dati, @NotBlank String motivo) {}
