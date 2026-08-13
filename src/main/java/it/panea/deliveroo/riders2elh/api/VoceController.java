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
import it.panea.deliveroo.riders2elh.repository.VoceRow;
import it.panea.deliveroo.riders2elh.service.VoceService;
import jakarta.validation.Valid;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Tag(name = "Voci", description = "Ingestione (CSV multipart) e consultazione delle voci di "
        + "pagamento, con annullamento logico e rettifica")
@RestController
@RequestMapping("/api/v1/voci")
public class VoceController {

    private final VoceService service;

    public VoceController(VoceService service) {
        this.service = service;
    }

    /** POST /api/v1/voci — ingestione di voci.csv (§ 9), upload multipart. */
    @Operation(summary = "Ingestione voci da CSV",
            description = """
                    Carica voci.csv come upload multipart/form-data, nella parte 'file'. \
                    Colonne attese nell'header: id_voce, descrizione, mese_riferimento_richiesto.

                    **Questo endpoint accetta solo multipart/form-data**, non JSON: inviare JSON \
                    produce 415. Da client non-browser va usato -F 'file=@voci.csv' (curl) o \
                    Body -> form-data (Postman) senza impostare Content-Type a mano, che \
                    sovrascriverebbe il boundary generato.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Tutte le voci caricate (nessun KO)"),
            @ApiResponse(responseCode = "207", description = "Caricamento parziale: almeno una voce "
                    + "in errore"),
            @ApiResponse(responseCode = "400", description = "Parte 'file' mancante o CSV illeggibile",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "415", description = "Content-Type non multipart/form-data",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<BatchEsitoResponse> carica(
            @Parameter(description = "File CSV delle voci")
            @RequestParam("file") MultipartFile file) throws IOException {
        List<VoceDto> lista = new ArrayList<>();
        try (var reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader)) {
            for (CSVRecord riga : parser) {
                lista.add(new VoceDto(
                        riga.get("id_voce"),
                        riga.get("descrizione"),
                        Boolean.parseBoolean(riga.get("mese_riferimento_richiesto"))));
            }
        }
        String checksum = ChecksumUtils.sha256(lista.toString());
        BatchEsitoResponse esito = service.carica(lista, file.getOriginalFilename(), checksum, SecurityUtils.clientIdAutenticato());
        return ResponseEntity.status(esito.recordKo() == 0 ? 201 : 207).body(esito);
    }

    @Operation(summary = "Elenco delle voci correnti",
            description = "Voci attualmente valide, dalla vista VW_VOCE_CORRENTE "
                    + "(FLAG_ULTIMA_VERSIONE='S' e STATO_RECORD='ATTIVO').")
    @ApiResponse(responseCode = "200", description = "Elenco delle voci correnti")
    @GetMapping
    public ResponseEntity<List<VoceRow>> leggiCorrenti() {
        return ResponseEntity.ok(service.leggiCorrenti());
    }

    @Operation(summary = "Storico completo di una voce",
            description = "Tutte le versioni della voce, annullate incluse, dalla tabella "
                    + "storicizzata T_VOCE_ST.")
    @ApiResponse(responseCode = "200", description = "Elenco delle versioni (vuoto se la voce non "
            + "è mai esistita)")
    @GetMapping("/{idVoce}/storico")
    public ResponseEntity<List<VoceRow>> leggiStorico(
            @Parameter(description = "Identificativo della voce", example = "V001")
            @PathVariable String idVoce) {
        return ResponseEntity.ok(service.leggiStorico(idVoce));
    }

    /** DELETE /api/v1/voci/{idVoce} — annullamento logico (§ 9.3). */
    @Operation(summary = "Annullamento logico di una voce",
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
    @DeleteMapping("/{idVoce}")
    public ResponseEntity<AnnullamentoResponse> annulla(
            @Parameter(description = "Identificativo della voce", example = "V001")
            @PathVariable String idVoce,
            @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idVoce, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/voci/{idVoce}/rettifica (§ 9.3). */
    @Operation(summary = "Rettifica di una voce",
            description = "Corregge i dati della voce inserendo una nuova versione corrente e "
                    + "registrando il motivo nel batch di rettifica.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Rettifica registrata"),
            @ApiResponse(responseCode = "400", description = "Dati o motivo non validi",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class))),
            @ApiResponse(responseCode = "404", description = "Nessuna versione corrente da rettificare",
                    content = @Content(schema = @Schema(implementation = ErroreResponse.class)))
    })
    @PostMapping("/{idVoce}/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(
            @Parameter(description = "Identificativo della voce", example = "V001")
            @PathVariable String idVoce,
            @RequestBody @Valid RettificaVoceRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idVoce, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
