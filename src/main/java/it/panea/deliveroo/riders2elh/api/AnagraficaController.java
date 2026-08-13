package it.panea.deliveroo.riders2elh.api;

import it.panea.deliveroo.riders2elh.common.ChecksumUtils;
import it.panea.deliveroo.riders2elh.common.SecurityUtils;
import it.panea.deliveroo.riders2elh.dto.*;
import it.panea.deliveroo.riders2elh.repository.RiderAnagraficaRow;
import it.panea.deliveroo.riders2elh.service.AnagraficaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class AnagraficaController {

    private final AnagraficaService service;

    public AnagraficaController(AnagraficaService service) {
        this.service = service;
    }

    /** POST /api/v1/anagrafiche — ingestione di anagrafica.json (§ 9). */
    @PostMapping("/anagrafiche")
    public ResponseEntity<BatchEsitoResponse> carica(@RequestBody @Valid List<RiderAnagraficaDto> lista) {
        String checksum = ChecksumUtils.sha256(lista.toString());
        BatchEsitoResponse esito = service.carica(lista, "anagrafica.json", checksum, SecurityUtils.clientIdAutenticato());
        return ResponseEntity.status(esito.recordKo() == 0 ? 201 : 207).body(esito);
    }

    /** GET /api/v1/rider/{idRider}/anagrafica — stato corrente o storico (§ 9). */
    @GetMapping("/rider/{idRider}/anagrafica")
    public ResponseEntity<?> leggi(@PathVariable String idRider,
                                    @RequestParam(defaultValue = "false") boolean storico) {
        if (storico) {
            return ResponseEntity.ok(service.leggiStorico(idRider));
        }
        return ResponseEntity.ok(service.leggiCorrente(idRider));
    }

    /** DELETE /api/v1/rider/{idRider}/anagrafica — annullamento logico (§ 9.3). */
    @DeleteMapping("/rider/{idRider}/anagrafica")
    public ResponseEntity<AnnullamentoResponse> annulla(@PathVariable String idRider,
                                                          @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idRider, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/rider/{idRider}/anagrafica/rettifica (§ 9.3). */
    @PostMapping("/rider/{idRider}/anagrafica/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(@PathVariable String idRider,
                                                          @RequestBody @Valid RettificaAnagraficaRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idRider, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
