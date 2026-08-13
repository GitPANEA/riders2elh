package it.panea.deliveroo.riders2elh.api;

import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riders2elh.dto.MotivoRequest;
import it.panea.deliveroo.riders2elh.repository.BatchRow;
import it.panea.deliveroo.riders2elh.service.BatchQueryService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/batch")
public class BatchController {

    private final BatchQueryService service;

    public BatchController(BatchQueryService service) {
        this.service = service;
    }

    /** GET /api/v1/batch/{idBatch} — esito elaborazione, errori associati (§ 9). */
    @GetMapping("/{idBatch}")
    public ResponseEntity<BatchRow> leggi(@PathVariable long idBatch) {
        return ResponseEntity.ok(service.leggi(idBatch));
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
    @GetMapping
    public ResponseEntity<List<BatchRow>> elenca(
            @RequestParam(required = false) String tipoOperazione,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataInizio,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dataFine) {
        return ResponseEntity.ok(service.elenca(tipoOperazione, dataInizio, dataFine));
    }

    /** DELETE /api/v1/batch/{idBatch} — annulla in blocco un intero file caricato per errore (§ 9.3). */
    @DeleteMapping("/{idBatch}")
    public ResponseEntity<AnnullamentoBatchResponse> annulla(@PathVariable long idBatch,
                                                              @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annullaBatch(idBatch, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
