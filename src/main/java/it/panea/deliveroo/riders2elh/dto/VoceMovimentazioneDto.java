package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** Riga di modifiche_integrazioni o di prospetto_finale — stessa forma, § 2. */
public record VoceMovimentazioneDto(
        @JsonProperty("id_voce") String idVoce,
        @JsonProperty("mese_riferimento") String meseRiferimento,
        @JsonProperty("importo_lordo") BigDecimal importoLordo,
        @JsonProperty("ritenuta_percentuale") BigDecimal ritenutaPercentuale,
        @JsonProperty("ritenuta_importo") BigDecimal ritenutaImporto,
        @JsonProperty("iva_percentuale") BigDecimal ivaPercentuale,
        @JsonProperty("iva_importo") BigDecimal ivaImporto,
        BigDecimal totale
) {}
