package it.panea.deliveroo.riders2elh.api;

import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.common.TipoOperazione;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riders2elh.dto.MotivoRequest;
import it.panea.deliveroo.riders2elh.repository.BatchRow;
import it.panea.deliveroo.riders2elh.service.BatchQueryService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /** GET /api/v1/batch?tipoOperazione=ANNULLAMENTO|RETTIFICA — log di audit (§ 9.3). */
    @GetMapping
    public ResponseEntity<List<BatchRow>> elenca(@RequestParam(required = false) TipoOperazione tipoOperazione) {
        return ResponseEntity.ok(service.elenca(tipoOperazione));
    }

    /** DELETE /api/v1/batch/{idBatch} — annulla in blocco un intero file caricato per errore (§ 9.3). */
    @DeleteMapping("/{idBatch}")
    public ResponseEntity<AnnullamentoBatchResponse> annulla(@PathVariable long idBatch,
                                                              @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annullaBatch(idBatch, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
