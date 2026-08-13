package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

public record RiepilogoDto(
        @JsonProperty("imposta_bollo") BigDecimal impostaBollo,
        @JsonProperty("percentuale_trattenute_fiscali") BigDecimal percentualeTrattenuteFiscali,
        @JsonProperty("importo_trattenute_fiscali") BigDecimal importoTrattenuteFiscali,
        @JsonProperty("percentuale_trattenute_previdenziali") BigDecimal percentualeTrattenutePrevidenziali,
        @JsonProperty("importo_trattenute_previdenziali") BigDecimal importoTrattenutePrevidenziali,
        @JsonProperty("pagamenti_contanti_gia_riscossi") BigDecimal pagamentiContantiGiaRiscossi,
        @JsonProperty("totale_dovuto") BigDecimal totaleDovuto
) {}
