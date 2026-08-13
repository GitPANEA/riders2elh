package it.panea.deliveroo.riders2elh.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import it.panea.deliveroo.riders2elh.common.ChecksumUtils;
import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.dto.*;
import it.panea.deliveroo.riders2elh.repository.MovimentazioneHeaderRow;
import it.panea.deliveroo.riders2elh.repository.MovimentazioneRow;
import it.panea.deliveroo.riders2elh.repository.MovimentazioneVersioneSintesi;
import it.panea.deliveroo.riders2elh.service.MovimentazioneService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Movimentazioni", description = "Ingestione e consultazione delle movimentazioni "
        + "(movimentazioni.json), con dettaglio consegne e voci, annullamento logico e rettifica")
@RestController
@RequestMapping("/api/v1")
public class MovimentazioneController {

    private final MovimentazioneService service;

    public MovimentazioneController(MovimentazioneService service) {
        this.service = service;
    }

    /** POST /api/v1/movimentazioni — ingestione di movimentazioni.json (§ 9). */
    @Operation(summary = "Ingestione movimentazioni",
            description = """
                    Carica movimentazioni.json, comprensivo del dettaglio consegne e voci. Ogni \
                    record genera una nuova versione datata; le tabelle di dettaglio seguono il \
                    partizionamento del padre (PARTITION BY REFERENCE).

                    Gli errori sono per record e non interrompono il caricamento: vengono \
                    registrati in T_BATCH_CARICAMENTO_ERRORE.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tutte le movimentazioni caricate"),
            @ApiResponse(responseCode = "207", description = "Caricamento parziale: almeno un record "
                    + "in errore"),
            @ApiResponse(responseCode = "400", description = "Payload non valido",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping("/movimentazioni")
    public ResponseEntity<BatchEsitoResponse> carica(@RequestBody @Valid List<MovimentazioneDto> lista) {
        String checksum = ChecksumUtils.sha256(lista.toString());
        BatchEsitoResponse esito = service.carica(lista, "movimentazioni.json", checksum, SecurityUtils.clientIdAutenticato());
        return ResponseEntity.status(esito.recordKo() == 0 ? 201 : 207).body(esito);
    }

    /** GET /api/v1/rider/{idRider}/movimentazioni — elenco correnti, filtrabile per periodo (§ 9). */
    @Operation(summary = "Movimentazioni correnti di un rider",
            description = "Elenco delle movimentazioni correnti del rider, in forma di sola "
                    + "testata. I due parametri di periodo filtrano sulle date di competenza "
                    + "della movimentazione (PERIODO_DA/PERIODO_A) e sono indipendenti dalla data "
                    + "di caricamento.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Elenco delle movimentazioni "
                    + "(eventualmente vuoto)"),
            @ApiResponse(responseCode = "404", description = "Rider inesistente",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping("/rider/{idRider}/movimentazioni")
    public ResponseEntity<List<MovimentazioneHeaderRow>> leggiPerRider(
            @Parameter(description = "Identificativo del rider", example = "RID001")
            @PathVariable String idRider,
            @Parameter(description = "Inizio del periodo di competenza (yyyy-MM-dd)",
                    example = "2026-06-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodoDa,
            @Parameter(description = "Fine del periodo di competenza (yyyy-MM-dd)",
                    example = "2026-06-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodoA) {
        return ResponseEntity.ok(service.leggiCorrentiPerRider(idRider, periodoDa, periodoA));
    }

    @Operation(summary = "Movimentazione corrente, con dettaglio completo",
            description = "Versione corrente della movimentazione, comprensiva del dettaglio "
                    + "consegne e voci.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Movimentazione trovata"),
            @ApiResponse(responseCode = "404", description = "Movimentazione inesistente o senza "
                    + "versione corrente attiva",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @GetMapping("/movimentazioni/{idMovimentazione}")
    public ResponseEntity<MovimentazioneRow> leggiCorrente(
            @Parameter(description = "Identificativo della movimentazione", example = "MOV001")
            @PathVariable String idMovimentazione) {
        return ResponseEntity.ok(service.leggiCorrente(idMovimentazione));
    }

    /** GET /api/v1/movimentazioni/{idMovimentazione}/storico (§ 9, § 10). */
    @Operation(summary = "Storico delle versioni di una movimentazione",
            description = "Elenco in sintesi di tutte le versioni, annullate incluse: serve a "
                    + "ricostruire la successione delle correzioni nel tempo. Per il dettaglio "
                    + "completo di una singola versione si usa l'endpoint della corrente.")
    @ApiResponse(responseCode = "200", description = "Elenco delle versioni (vuoto se la "
            + "movimentazione non è mai esistita)")
    @GetMapping("/movimentazioni/{idMovimentazione}/storico")
    public ResponseEntity<List<MovimentazioneVersioneSintesi>> leggiStorico(
            @Parameter(description = "Identificativo della movimentazione", example = "MOV001")
            @PathVariable String idMovimentazione) {
        return ResponseEntity.ok(service.leggiStorico(idMovimentazione));
    }

    /** DELETE /api/v1/movimentazioni/{idMovimentazione} — annullamento logico (§ 9.3). */
    @Operation(summary = "Annullamento logico di una movimentazione",
            description = "Nonostante il verbo DELETE **non cancella nulla**: inserisce una nuova "
                    + "versione con STATO_RECORD='ANNULLATO'.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Annullamento registrato"),
            @ApiResponse(responseCode = "400", description = "Motivo assente o non valido",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessuna versione corrente da annullare",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "409", description = "Conflitto di concorrenza",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @DeleteMapping("/movimentazioni/{idMovimentazione}")
    public ResponseEntity<AnnullamentoResponse> annulla(
            @Parameter(description = "Identificativo della movimentazione", example = "MOV001")
            @PathVariable String idMovimentazione,
            @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idMovimentazione, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/movimentazioni/{idMovimentazione}/rettifica (§ 9.3). */
    @Operation(summary = "Rettifica di una movimentazione",
            description = "Corregge i dati della movimentazione inserendo una nuova versione "
                    + "corrente e registrando il motivo nel batch di rettifica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rettifica registrata"),
            @ApiResponse(responseCode = "400", description = "Dati o motivo non validi",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessuna versione corrente da rettificare",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping("/movimentazioni/{idMovimentazione}/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(
            @Parameter(description = "Identificativo della movimentazione", example = "MOV001")
            @PathVariable String idMovimentazione,
            @RequestBody @Valid RettificaMovimentazioneRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idMovimentazione, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
