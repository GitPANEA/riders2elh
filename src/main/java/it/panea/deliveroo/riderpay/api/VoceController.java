package it.panea.deliveroo.riderpay.api;

import it.panea.deliveroo.riderpay.common.ChecksumUtils;
import it.panea.deliveroo.riderpay.common.SecurityUtils;
import it.panea.deliveroo.riderpay.dto.*;
import it.panea.deliveroo.riderpay.repository.VoceRow;
import it.panea.deliveroo.riderpay.service.VoceService;
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

@RestController
@RequestMapping("/api/v1/voci")
public class VoceController {

    private final VoceService service;

    public VoceController(VoceService service) {
        this.service = service;
    }

    /** POST /api/v1/voci — ingestione di voci.csv (§ 9), upload multipart. */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<BatchEsitoResponse> carica(@RequestParam("file") MultipartFile file) throws IOException {
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

    @GetMapping
    public ResponseEntity<List<VoceRow>> leggiCorrenti() {
        return ResponseEntity.ok(service.leggiCorrenti());
    }

    @GetMapping("/{idVoce}/storico")
    public ResponseEntity<List<VoceRow>> leggiStorico(@PathVariable String idVoce) {
        return ResponseEntity.ok(service.leggiStorico(idVoce));
    }

    /** DELETE /api/v1/voci/{idVoce} — annullamento logico (§ 9.3). */
    @DeleteMapping("/{idVoce}")
    public ResponseEntity<AnnullamentoResponse> annulla(@PathVariable String idVoce,
                                                         @RequestBody @Valid MotivoRequest richiesta) {
        return ResponseEntity.ok(service.annulla(idVoce, richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }

    /** POST /api/v1/voci/{idVoce}/rettifica (§ 9.3). */
    @PostMapping("/{idVoce}/rettifica")
    public ResponseEntity<BatchEsitoResponse> rettifica(@PathVariable String idVoce,
                                                         @RequestBody @Valid RettificaVoceRequest richiesta) {
        return ResponseEntity.ok(service.rettifica(idVoce, richiesta.dati(), richiesta.motivo(), SecurityUtils.clientIdAutenticato()));
    }
}
