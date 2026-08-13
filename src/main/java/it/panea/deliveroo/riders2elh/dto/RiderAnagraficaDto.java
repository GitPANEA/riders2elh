package it.panea.deliveroo.riders2elh.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record RiderAnagraficaDto(
        @JsonProperty("id_rider") @NotBlank String idRider,
        @JsonProperty("regime_fiscale") @NotBlank String regimeFiscale,
        @JsonProperty("data_inizio") @NotNull LocalDate dataInizio,
        @JsonProperty("data_fine") LocalDate dataFine,
        @NotBlank String nome,
        @NotBlank String cognome,
        @JsonProperty("codice_fiscale") @NotBlank String codiceFiscale,
        @JsonProperty("partita_iva") String partitaIva,
        // Facoltativi e senza vincolo di formato: il controllo di validazione qui
        // sarebbe su tutta la lista (@Valid sul List nel controller), quindi un solo
        // valore anomalo respingerebbe l'intero file con 400 invece di finire come
        // errore del singolo record. I @Size replicano solo la larghezza delle
        // colonne, per intercettare il troncamento prima dell'ORA-12899.
        @JsonProperty("telefono_cellulare") @Size(max = 20) String telefonoCellulare,
        @JsonProperty("email") @Size(max = 200) String email,
        @JsonProperty("indirizzo_residenza") String indirizzoResidenza,
        @JsonProperty("codice_istat_residenza") String codiceIstatResidenza,
        @JsonProperty("comune_residenza") String comuneResidenza,
        @JsonProperty("provincia_residenza") String provinciaResidenza,
        @JsonProperty("cap_residenza") String capResidenza
) {}
