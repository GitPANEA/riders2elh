package it.panea.deliveroo.riders2elh.dto;

import jakarta.validation.constraints.NotBlank;

/** Body di ogni DELETE e di ogni POST .../rettifica (§ 9.3). */
public record MotivoRequest(@NotBlank String motivo) {}
