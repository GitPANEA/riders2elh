package it.panea.deliveroo.riders2elh.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riders2elh.dto.ErroreResponse;
import it.panea.deliveroo.riders2elh.dto.MotivoRequest;
import it.panea.deliveroo.riders2elh.repository.BatchErroreRow;
import it.panea.deliveroo.riders2elh.repository.BatchRow;
import it.panea.deliveroo.riders2elh.service.BatchQueryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Batch", description = "Audit dei caricamenti: esito dei batch, ricerca per tipo "
        + "operazione e intervallo di date, annullamento in blocco")
@RestController
@RequestMapping("/api/v1/batch")
public class BatchController {

    private final BatchQueryService service;

    public BatchController(BatchQueryService service) {
        this.service = service;
    }

    /** GET /api/v1/batch/{idBatch} — esito elaborazione, errori associati (§ 9). */
    @Operation(summary = "Esito di un singolo batch",
            description = """
                    Restituisce l'esito dell'elaborazione di un batch: contatori dei record \
                    elaborati, data di fine elaborazione, eventuale batch di riferimento \
                    (valorizzato su annullamenti e rettifiche).

                    Le tre POST di caricamento sono asincrone: rispondono subito 202 con l'id \
                    batch, quindi questo endpoint è il punto di polling per conoscere l'esito. \
                    esito=IN_CORSO significa elaborazione ancora in corso (i contatori non sono \
                    definitivi); esito=ERRORE_TECNICO significa che il task si è interrotto per \
                    un problema tecnico, non per i dati. probabilmenteBloccato=true segnala un \
                    batch IN_CORSO da più della soglia configurata: nessuna scrittura avviene per \
                    calcolarlo, è solo un avviso.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Batch trovato"),
            @ApiResponse(responseCode = "404", description = "Nessun batch con questo id",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping("/{idBatch}")
    public ResponseEntity<BatchRow> leggi(
            @Parameter(description = "Identificativo del batch", example = "17")
            @PathVariable long idBatch) {
        return ResponseEntity.ok(service.leggi(idBatch));
    }

    /** GET /api/v1/batch/{idBatch}/errori — righe anomale registrate durante l'elaborazione del batch. */
    @Operation(summary = "Righe anomale di un batch",
            description = "Elenca i record scartati durante l'elaborazione del batch (validazione "
                    + "di formato/coerenza o errore tecnico), con numero di riga nel payload "
                    + "originale, chiave business, messaggio d'errore e payload JSON del record. "
                    + "Risposta paginata: un batch con molti record può generare molte righe di errore.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Pagina di righe anomale (eventualmente vuota)"),
            @ApiResponse(responseCode = "400", description = "page o size non validi",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessun batch con questo id",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping("/{idBatch}/errori")
    public ResponseEntity<List<BatchErroreRow>> errori(
            @Parameter(description = "Identificativo del batch", example = "17")
            @PathVariable long idBatch,
            @Parameter(description = "Numero di pagina, a partire da 0") @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Dimensione della pagina") @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(service.elencaErrori(idBatch, page, size));
    }

    /**
     * GET /api/v1/batch — log di audit (§ 9.3), con filtri opzionali e combinabili:
     * <ul>
     *   <li>{@code ?tipoOperazione=CARICAMENTO|RETTIFICA|ANNULLAMENTO} — accettato in qualunque
     *       combinazione di maiuscole/minuscole; un valore non riconosciuto → 400 con l'elenco
     *       dei valori ammessi. Il parametro è dichiarato {@code String} e non
     *       {@code TipoOperazione} proprio per potersi occupare della normalizzazione: con
     *       l'enum, la conversione avviene in Spring prima che il codice veda il valore, e un
     *       valore errato diventerebbe un 500.</li>
     *   <li>{@code ?dataInizio=2026-08-13&dataFine=2026-08-15} — batch ricevuti nell'intervallo
     *       di giorni, <b>estremi inclusi</b>, confrontati su {@code DT_RICEZIONE}. Le due date
     *       vanno insieme (una sola → 400) e si indicano senza orario ({@code yyyy-MM-dd}).</li>
     * </ul>
     * Nessuna corrispondenza è una risposta legittima: {@code 200} con lista vuota, non 404.
     */
    @Operation(summary = "Elenco batch, con filtri opzionali e combinabili",
            description = """
                    Log di audit dei batch. Tutti i filtri sono opzionali e si combinano in AND: \
                    nessun filtro restituisce tutti i batch.

                    Il filtro per data confronta DT_RICEZIONE e comprende **entrambi gli estremi** \
                    dei giorni indicati. Le due date vanno passate insieme: una sola darebbe un \
                    intervallo aperto, che non è la semantica dell'endpoint, e produce 400.

                    Nessuna conversione di fuso orario: il giorno di ricerca è il giorno come sta \
                    scritto in DT_RICEZIONE.

                    Nessuna corrispondenza è una risposta legittima: 200 con lista vuota, non 404.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Elenco dei batch corrispondenti "
                    + "(eventualmente vuoto)"),
            @ApiResponse(responseCode = "400", description = "Parametri incoerenti: tipoOperazione "
                    + "non riconosciuto, una sola delle due date, oppure dataInizio successiva a "
                    + "dataFine",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping
    public ResponseEntity<List<BatchRow>> elenca(
            @Parameter(description = "Filtro sul tipo di operazione. Case-insensitive: "
                    + "'annullamento' equivale ad 'ANNULLAMENTO'.",
                    schema = @Schema(allowableValues = {"CARICAMENTO", "RETTIFICA", "ANNULLAMENTO"}),
                    example = "ANNULLAMENTO")
            @RequestParam(required = false) String tipoOperazione,

            @Parameter(description = "Primo giorno dell'intervallo, incluso (yyyy-MM-dd, senza "
                    + "orario). Va passato insieme a dataFine.", example = "2026-08-13")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInizio,

            @Parameter(description = "Ultimo giorno dell'intervallo, incluso per l'intera "
                    + "giornata (yyyy-MM-dd, senza orario). Va passato insieme a dataInizio.",
                    example = "2026-08-15")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFine) {
        return ResponseEntity.ok(service.elenca(tipoOperazione, dataInizio, dataFine));
    }

    /** DELETE /api/v1/batch/{idBatch} — annulla in blocco un intero file caricato per errore (§ 9.3). */
    @Operation(summary = "Annullamento in blocco di un intero batch",
            description = """
                    Annulla tutti i record correnti caricati da un batch — il caso d'uso è un \
                    file inviato per errore.

                    Coerentemente col modello append-only, **non cancella nulla**: apre un nuovo \
                    batch di tipo ANNULLAMENTO e per ogni record inserisce una nuova versione con \
                    STATO_RECORD='ANNULLATO'. Lo storico resta interrogabile.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Annullamento eseguito: la risposta "
                    + "riporta il nuovo id di batch e il numero di record annullati"),
            @ApiResponse(responseCode = "400", description = "Motivo assente o non valido",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessun batch con questo id",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @DeleteMapping("/{idBatch}")
    public ResponseEntity<AnnullamentoBatchResponse> annulla(
            @Parameter(description = "Identificativo del batch da annullare", example = "17")
            @PathVariable long idBatch,
            @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annullaBatch(idBatch, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
