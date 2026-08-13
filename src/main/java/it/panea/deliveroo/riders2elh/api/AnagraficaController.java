package it.panea.deliveroo.riders2elh.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.panea.deliveroo.riders2elh.common.ChecksumUtils;
import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.dto.*;
import it.panea.deliveroo.riders2elh.repository.RiderAnagraficaRow;
import it.panea.deliveroo.riders2elh.service.AnagraficaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Anagrafica", description = "Ingestione e consultazione delle anagrafiche rider "
        + "(anagrafica.json), con annullamento logico e rettifica")
@RestController
@RequestMapping("/api/v1")
public class AnagraficaController {

    private final AnagraficaService service;

    public AnagraficaController(AnagraficaService service) {
        this.service = service;
    }

    /** POST /api/v1/anagrafiche — ingestione di anagrafica.json (§ 9). */
    @Operation(summary = "Ingestione anagrafiche",
            description = """
                    Carica il contenuto di anagrafica.json. Ogni record genera una nuova versione \
                    datata: la versione precedente della stessa chiave business viene chiusa \
                    (FLAG_ULTIMA_VERSIONE='N') e la nuova inserita, nella stessa transazione.

                    Gli errori sono per record e non interrompono il caricamento: i record KO \
                    vengono registrati in T_BATCH_CARICAMENTO_ERRORE e il batch prosegue.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tutti i record caricati (nessun KO)"),
            @ApiResponse(responseCode = "207", description = "Caricamento parziale: almeno un "
                    + "record in errore. I contatori nella risposta indicano quanti."),
            @ApiResponse(responseCode = "400", description = "Payload non valido",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping("/anagrafiche")
    public ResponseEntity<BatchEsitoResponse> carica(@RequestBody @Valid List<RiderAnagraficaDto> lista) {
        String checksum = ChecksumUtils.sha256(lista.toString());
        BatchEsitoResponse esito = service.carica(lista, "anagrafica.json", checksum, SecurityUtils.clientIdAutenticato());
        return ResponseEntity.status(esito.recordKo() == 0 ? 201 : 207).body(esito);
    }

    /** GET /api/v1/rider/{idRider}/anagrafica — stato corrente o storico (§ 9). */
    @Operation(summary = "Anagrafica corrente o storico completo di un rider",
            description = """
                    Con storico=false (default) restituisce la sola versione corrente, letta dalla \
                    vista VW_*_CORRENTE (FLAG_ULTIMA_VERSIONE='S' e STATO_RECORD='ATTIVO').

                    Con storico=true restituisce **tutte** le versioni dalla tabella storicizzata, \
                    annullate incluse: è la fonte per ricostruire cosa si sapeva e quando.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Versione corrente (oggetto singolo) "
                    + "oppure elenco delle versioni se storico=true",
                    content = @Content(array = @ArraySchema(
                            schema = @Schema(implementation = RiderAnagraficaRow.class)))),
            @ApiResponse(responseCode = "404", description = "Rider inesistente o senza versione "
                    + "corrente attiva",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping("/rider/{idRider}/anagrafica")
    public ResponseEntity<?> leggi(
            @Parameter(description = "Identificativo del rider", example = "RID001")
            @PathVariable String idRider,
            @Parameter(description = "true = tutte le versioni storiche; false = solo la corrente")
            @RequestParam(defaultValue = "false") boolean storico) {
        if (storico) {
            return ResponseEntity.ok(service.leggiStorico(idRider));
        }
        return ResponseEntity.ok(service.leggiCorrente(idRider));
    }

    /** DELETE /api/v1/rider/{idRider}/anagrafica — annullamento logico (§ 9.3). */
    @Operation(summary = "Annullamento logico dell'anagrafica di un rider",
            description = "Nonostante il verbo DELETE **non cancella nulla**: inserisce una nuova "
                    + "versione con STATO_RECORD='ANNULLATO'. Il record esce dalla vista corrente "
                    + "ma resta nello storico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Annullamento registrato"),
            @ApiResponse(responseCode = "400", description = "Motivo assente o non valido",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessuna versione corrente da annullare",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflitto di concorrenza: la versione "
                    + "corrente è cambiata durante l'operazione",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @DeleteMapping("/rider/{idRider}/anagrafica")
    public ResponseEntity<AnnullamentoResponse> annulla(
            @Parameter(description = "Identificativo del rider", example = "RID001")
            @PathVariable String idRider,
            @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idRider, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/rider/{idRider}/anagrafica/rettifica (§ 9.3). */
    @Operation(summary = "Rettifica dell'anagrafica di un rider",
            description = "Corregge i dati inserendo una nuova versione corrente e registrando il "
                    + "motivo nel batch di rettifica. La versione precedente resta consultabile "
                    + "nello storico.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rettifica registrata"),
            @ApiResponse(responseCode = "400", description = "Dati o motivo non validi",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessuna versione corrente da rettificare",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping("/rider/{idRider}/anagrafica/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(
            @Parameter(description = "Identificativo del rider", example = "RID001")
            @PathVariable String idRider,
            @RequestBody @Valid RettificaAnagraficaRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idRider, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
