package it.panea.deliveroo.riderpay.api;

import it.panea.deliveroo.riderpay.common.ChecksumUtils;
import it.panea.deliveroo.riderpay.common.SecurityUtils;
import it.panea.deliveroo.riderpay.dto.*;
import it.panea.deliveroo.riderpay.repository.MovimentazioneHeaderRow;
import it.panea.deliveroo.riderpay.repository.MovimentazioneRow;
import it.panea.deliveroo.riderpay.repository.MovimentazioneVersioneSintesi;
import it.panea.deliveroo.riderpay.service.MovimentazioneService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class MovimentazioneController {

    private final MovimentazioneService service;

    public MovimentazioneController(MovimentazioneService service) {
        this.service = service;
    }

    /** POST /api/v1/movimentazioni — ingestione di movimentazioni.json (§ 9). */
    @PostMapping("/movimentazioni")
    public ResponseEntity<BatchEsitoResponse> carica(@RequestBody @Valid List<MovimentazioneDto> lista) {
        String checksum = ChecksumUtils.sha256(lista.toString());
        BatchEsitoResponse esito = service.carica(lista, "movimentazioni.json", checksum, SecurityUtils.clientIdAutenticato());
        return ResponseEntity.status(esito.recordKo() == 0 ? 201 : 207).body(esito);
    }

    /** GET /api/v1/rider/{idRider}/movimentazioni — elenco correnti, filtrabile per periodo (§ 9). */
    @GetMapping("/rider/{idRider}/movimentazioni")
    public ResponseEntity<List<MovimentazioneHeaderRow>> leggiPerRider(
            @PathVariable String idRider,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodoDa,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodoA) {
        return ResponseEntity.ok(service.leggiCorrentiPerRider(idRider, periodoDa, periodoA));
    }

    @GetMapping("/movimentazioni/{idMovimentazione}")
    public ResponseEntity<MovimentazioneRow> leggiCorrente(@PathVariable String idMovimentazione) {
        return ResponseEntity.ok(service.leggiCorrente(idMovimentazione));
    }

    /** GET /api/v1/movimentazioni/{idMovimentazione}/storico (§ 9, § 10). */
    @GetMapping("/movimentazioni/{idMovimentazione}/storico")
    public ResponseEntity<List<MovimentazioneVersioneSintesi>> leggiStorico(@PathVariable String idMovimentazione) {
        return ResponseEntity.ok(service.leggiStorico(idMovimentazione));
    }

    /** DELETE /api/v1/movimentazioni/{idMovimentazione} — annullamento logico (§ 9.3). */
    @DeleteMapping("/movimentazioni/{idMovimentazione}")
    public ResponseEntity<AnnullamentoResponse> annulla(@PathVariable String idMovimentazione,
                                                          @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idMovimentazione, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/movimentazioni/{idMovimentazione}/rettifica (§ 9.3). */
    @PostMapping("/movimentazioni/{idMovimentazione}/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(@PathVariable String idMovimentazione,
                                                          @RequestBody @Valid RettificaMovimentazioneRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idMovimentazione, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
