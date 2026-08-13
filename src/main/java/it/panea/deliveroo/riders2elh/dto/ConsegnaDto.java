package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConsegnaDto(
        LocalDate data,
        @JsonProperty("numero_consegne") int numeroConsegne,
        @JsonProperty("totale_parziale_lordo") BigDecimal totaleParzialeLordo
) {}
