package it.panea.deliveroo.riders2elh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.panea.deliveroo.riders2elh.common.*;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoResponse;
import it.panea.deliveroo.riders2elh.dto.BatchEsitoResponse;
import it.panea.deliveroo.riders2elh.dto.RiderAnagraficaDto;
import it.panea.deliveroo.riders2elh.repository.BatchCaricamentoRepository;
import it.panea.deliveroo.riders2elh.repository.MasterKeyRepository;
import it.panea.deliveroo.riders2elh.repository.RiderAnagraficaRepository;
import it.panea.deliveroo.riders2elh.repository.RiderAnagraficaRow;
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
public class AnagraficaService {

    private static final Logger log = LoggerFactory.getLogger(AnagraficaService.class);

    private final RiderAnagraficaRepository repository;
    private final MasterKeyRepository masterKeyRepository;
    private final BatchCaricamentoRepository batchRepository;
    private final ObjectMapper objectMapper;

    public AnagraficaService(RiderAnagraficaRepository repository, MasterKeyRepository masterKeyRepository,
                              BatchCaricamentoRepository batchRepository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.masterKeyRepository = masterKeyRepository;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Flusso § 9.1, avvio: crea il batch e ritorna subito, senza elaborare la lista.
     * Il controller risponde 202 con l'id batch e affida l'elaborazione a
     * {@link #elaboraAsync}, invocato dal controller stesso (una self-invocation da
     * questo service non passerebbe dal proxy Spring che rende @Async effettivo).
     */
    public long avviaCaricamento(List<RiderAnagraficaDto> lista, String nomeFileOrigine, String checksum, String clientId) {
        return batchRepository.creaBatch(TipoEntita.ANAGRAFICA, TipoOperazione.CARICAMENTO, null,
                null, nomeFileOrigine, "JSON", clientId, checksum);
    }

    /**
     * Elaborazione effettiva, su thread separato (pool "batchTaskExecutor", § 9.1). Non
     * @Transactional a livello di metodo, per lo stesso motivo di sempre: ogni record ha
     * la propria unità atomica in repository.sostituisciVersioneCorrente, così un record
     * malformato finisce in T_BATCH_CARICAMENTO_ERRORE senza fare rollback dei record già
     * scritti correttamente nello stesso batch.
     */
    @Async("batchTaskExecutor")
    public void elaboraAsync(long idBatch, List<RiderAnagraficaDto> lista) {
        int ok = 0, ko = 0;
        try {
            batchRepository.avviaElaborazione(idBatch, lista.size());
            for (int i = 0; i < lista.size(); i++) {
                RiderAnagraficaDto dto = lista.get(i);
                try {
                    caricaSingolo(dto, idBatch);
                    ok++;
                } catch (Exception e) {
                    ko++;
                    log.error("Batch {}: caricamento fallito per il rider {}", idBatch, dto.idRider(), e);
                    batchRepository.registraErrore(idBatch, i, dto.idRider(), messaggioCompleto(e), payloadJson(dto));
                }
            }
            EsitoBatch esito = ko == 0 ? EsitoBatch.OK : (ok == 0 ? EsitoBatch.KO : EsitoBatch.PARZIALE);
            batchRepository.chiudiBatch(idBatch, esito, lista.size(), ok, ko);
        } catch (Exception e) {
            // Eccezione fuori dal ciclo per-record (es. avviaElaborazione/chiudiBatch
            // falliti): senza questo catch resterebbe solo nell'AsyncUncaughtExceptionHandler
            // di default di Spring, e il batch resterebbe bloccato in IN_CORSO per sempre.
            log.error("Batch {}: elaborazione interrotta da un errore tecnico", idBatch, e);
            try {
                batchRepository.chiudiBatch(idBatch, EsitoBatch.ERRORE_TECNICO, lista.size(), ok, ko);
            } catch (Exception e2) {
                log.error("Batch {}: impossibile marcare il batch come ERRORE_TECNICO, resta IN_CORSO", idBatch, e2);
            }
        }
    }

    private String payloadJson(RiderAnagraficaDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            return dto.toString();
        }
    }

    private void caricaSingolo(RiderAnagraficaDto dto, long idBatch) {
        List<String> violazioni = ValidatoreFormato.validaAnagrafica(dto);
        if (!violazioni.isEmpty()) {
            throw new RecordNonValidoException(violazioni);
        }
        masterKeyRepository.assicuraRider(dto.idRider());
        var correnteEsistente = repository.trovaVersioneCorrente(dto.idRider());
        if (correnteEsistente.isPresent() && correnteEsistente.get().dati().equals(dto)) {
            return; // re-invio invariato: no-op, il batch resta comunque tracciato
        }
        repository.sostituisciVersioneCorrente(dto, idBatch, StatoRecord.ATTIVO);
    }

    public RiderAnagraficaRow leggiCorrente(String idRider) {
        return repository.trovaVersioneCorrente(idRider)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna anagrafica corrente per rider " + idRider));
    }

    public List<RiderAnagraficaRow> leggiStorico(String idRider) {
        return repository.elencaStorico(idRider);
    }

    /** § 9.3: DELETE — annullamento logico della versione corrente. */
    @Transactional
    public AnnullamentoResponse annulla(String idRider, String motivo, String clientId) {
        RiderAnagraficaRow corrente = repository.trovaVersioneCorrente(idRider)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna anagrafica corrente da annullare per rider " + idRider));
        long idBatch = batchRepository.creaBatch(TipoEntita.ANAGRAFICA, TipoOperazione.ANNULLAMENTO,
                corrente.idBatchCaricamento(), motivo, null, null, clientId, null);
        try {
            repository.sostituisciVersioneCorrente(corrente.dati(), idBatch, StatoRecord.ANNULLATO);
        } catch (DataIntegrityViolationException e) {
            throw new ConflittoConcorrenzaException(
                    "Un'altra richiesta ha modificato l'anagrafica di " + idRider + " in concorrenza", e);
        }
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new AnnullamentoResponse(idBatch, TipoOperazione.ANNULLAMENTO, idRider, Instant.now());
    }

    /** § 9.3: rettifica — nuova versione corretta, esplicitamente motivata. */
    @Transactional
    public BatchEsitoResponse rettifica(String idRider, RiderAnagraficaDto datiCorretti, String motivo, String clientId) {
        RiderAnagraficaRow correnteErrata = repository.trovaVersioneCorrente(idRider)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna anagrafica corrente da rettificare per rider " + idRider));
        long idBatch = batchRepository.creaBatch(TipoEntita.ANAGRAFICA, TipoOperazione.RETTIFICA,
                correnteErrata.idBatchCaricamento(), motivo, null, null, clientId, null);
        repository.sostituisciVersioneCorrente(datiCorretti, idBatch, StatoRecord.ATTIVO);
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new BatchEsitoResponse(idBatch, TipoOperazione.RETTIFICA, EsitoBatch.OK, 1, 1, 0, Instant.now());
    }
}
