package it.panea.deliveroo.riderpay.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/** totali_modifiche_integrazioni: stessi importi di VoceMovimentazioneDto, senza id_voce/mese. */
public record TotaliVoceDto(
        @JsonProperty("importo_lordo") BigDecimal importoLordo,
        @JsonProperty("ritenuta_percentuale") BigDecimal ritenutaPercentuale,
        @JsonProperty("ritenuta_importo") BigDecimal ritenutaImporto,
        @JsonProperty("iva_percentuale") BigDecimal ivaPercentuale,
        @JsonProperty("iva_importo") BigDecimal ivaImporto,
        BigDecimal totale
) {}
