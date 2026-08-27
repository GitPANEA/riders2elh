package it.panea.deliveroo.riders2elh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.panea.deliveroo.riders2elh.common.*;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoResponse;
import it.panea.deliveroo.riders2elh.dto.BatchEsitoResponse;
import it.panea.deliveroo.riders2elh.dto.VoceDto;
import it.panea.deliveroo.riders2elh.repository.BatchCaricamentoRepository;
import it.panea.deliveroo.riders2elh.repository.MasterKeyRepository;
import it.panea.deliveroo.riders2elh.repository.VoceRepository;
import it.panea.deliveroo.riders2elh.repository.VoceRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static it.panea.deliveroo.riders2elh.common.DiagnosticaErrori.messaggioCompleto;

@Service
public class VoceService {

    private static final Logger log = LoggerFactory.getLogger(VoceService.class);

    private final VoceRepository repository;
    private final MasterKeyRepository masterKeyRepository;
    private final BatchCaricamentoRepository batchRepository;
    private final ObjectMapper objectMapper;

    public VoceService(VoceRepository repository, MasterKeyRepository masterKeyRepository,
                        BatchCaricamentoRepository batchRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.masterKeyRepository = masterKeyRepository;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Avvio: crea il batch e ritorna subito. Il controller risponde 202 con l'id batch
     * e affida l'elaborazione a {@link #elaboraAsync} (invocata dal controller, non da
     * qui: una self-invocation non passerebbe dal proxy Spring che rende @Async effettivo).
     */
    public long avviaCaricamento(List<VoceDto> lista, String nomeFileOrigine, String checksum, String clientId) {
        return batchRepository.creaBatch(TipoEntita.VOCE, TipoOperazione.CARICAMENTO, null,
                null, nomeFileOrigine, "CSV", clientId, checksum);
    }

    @Async("batchTaskExecutor")
    public void elaboraAsync(long idBatch, List<VoceDto> lista) {
        int ok = 0, ko = 0;
        try {
            batchRepository.avviaElaborazione(idBatch, lista.size());
            for (int i = 0; i < lista.size(); i++) {
                VoceDto dto = lista.get(i);
                try {
                    caricaSingola(dto, idBatch);
                    ok++;
                } catch (Exception e) {
                    ko++;
                    log.error("Batch {}: caricamento fallito per la voce {}", idBatch, dto.idVoce(), e);
                    batchRepository.registraErrore(idBatch, i, dto.idVoce(), messaggioCompleto(e), payloadJson(dto));
                }
            }
            EsitoBatch esito = ko == 0 ? EsitoBatch.OK : (ok == 0 ? EsitoBatch.KO : EsitoBatch.PARZIALE);
            batchRepository.chiudiBatch(idBatch, esito, lista.size(), ok, ko);
        } catch (Exception e) {
            log.error("Batch {}: elaborazione interrotta da un errore tecnico", idBatch, e);
            try {
                batchRepository.chiudiBatch(idBatch, EsitoBatch.ERRORE_TECNICO, lista.size(), ok, ko);
            } catch (Exception e2) {
                log.error("Batch {}: impossibile marcare il batch come ERRORE_TECNICO, resta IN_CORSO", idBatch, e2);
            }
        }
    }

    private String payloadJson(VoceDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            return dto.toString();
        }
    }

    private void caricaSingola(VoceDto dto, long idBatch) {
        List<String> violazioni = ValidatoreFormato.validaVoce(dto);
        if (!violazioni.isEmpty()) {
            throw new RecordNonValidoException(violazioni);
        }
        masterKeyRepository.assicuraVoce(dto.idVoce());
        var correnteEsistente = repository.trovaVersioneCorrente(dto.idVoce());
        if (correnteEsistente.isPresent() && correnteEsistente.get().dati().equals(dto)) {
            return;
        }
        repository.sostituisciVersioneCorrente(dto, idBatch, StatoRecord.ATTIVO);
    }

    public List<VoceRow> leggiCorrenti() {
        return repository.elencaCorrenti();
    }

    public List<VoceRow> leggiStorico(String idVoce) {
        return repository.elencaStorico(idVoce);
    }

    @Transactional
    public AnnullamentoResponse annulla(String idVoce, String motivo, String clientId) {
        VoceRow corrente = repository.trovaVersioneCorrente(idVoce)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna voce corrente da annullare: " + idVoce));
        long idBatch = batchRepository.creaBatch(TipoEntita.VOCE, TipoOperazione.ANNULLAMENTO,
                corrente.idBatchCaricamento(), motivo, null, null, clientId, null);
        try {
            repository.sostituisciVersioneCorrente(corrente.dati(), idBatch, StatoRecord.ANNULLATO);
        } catch (DataIntegrityViolationException e) {
            throw new ConflittoConcorrenzaException("Un'altra richiesta ha modificato la voce " + idVoce + " in concorrenza", e);
        }
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new AnnullamentoResponse(idBatch, TipoOperazione.ANNULLAMENTO, idVoce, Instant.now());
    }

    @Transactional
    public BatchEsitoResponse rettifica(String idVoce, VoceDto datiCorretti, String motivo, String clientId) {
        VoceRow correnteErrata = repository.trovaVersioneCorrente(idVoce)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna voce corrente da rettificare: " + idVoce));
        long idBatch = batchRepository.creaBatch(TipoEntita.VOCE, TipoOperazione.RETTIFICA,
                correnteErrata.idBatchCaricamento(), motivo, null, null, clientId, null);
        repository.sostituisciVersioneCorrente(datiCorretti, idBatch, StatoRecord.ATTIVO);
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new BatchEsitoResponse(idBatch, TipoOperazione.RETTIFICA, EsitoBatch.OK, 1, 1, 0, Instant.now());
    }
}
