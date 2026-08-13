package it.panea.deliveroo.riders2elh.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Forma del corpo di risposta in caso di errore, così come lo produce
 * {@code GlobalExceptionHandler}.
 * <p>
 * Esiste per la documentazione OpenAPI: l'handler costruisce a runtime una
 * {@code Map<String, Object>} con queste tre chiavi, e senza un tipo dichiarato Swagger UI
 * mostrerebbe un oggetto vuoto al posto dello schema. <b>Non è usata dal codice a runtime</b>
 * — se si cambiano le chiavi in {@code GlobalExceptionHandler.errore(...)}, va aggiornata
 * anche questa, perché nulla lo impone al compilatore.
 */
@Schema(name = "ErroreResponse", description = "Corpo di risposta degli errori gestiti")
public record ErroreResponse(

        @Schema(description = "Istante in cui l'errore è stato prodotto (ISO-8601)",
                example = "2026-08-13T13:17:42.214Z")
        String timestamp,

        @Schema(description = "Codice di stato HTTP, ripetuto nel corpo", example = "400")
        int status,

        @Schema(description = "Descrizione dell'errore, pensata per essere leggibile da chi "
                + "ha fatto la chiamata",
                example = "tipoOperazione non valido: 'PIPPO'. Valori ammessi: CARICAMENTO, "
                        + "RETTIFICA, ANNULLAMENTO.")
        String errore
) {}
