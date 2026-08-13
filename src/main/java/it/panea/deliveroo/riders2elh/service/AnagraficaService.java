package it.panea.deliveroo.riders2elh.service;

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

    public AnagraficaService(RiderAnagraficaRepository repository, MasterKeyRepository masterKeyRepository,
                              BatchCaricamentoRepository batchRepository) {
        this.repository = repository;
        this.masterKeyRepository = masterKeyRepository;
        this.batchRepository = batchRepository;
    }

    /**
     * Flusso § 9.1. Deliberatamente NON @Transactional: ogni record ha la propria
     * unità atomica in repository.sostituisciVersioneCorrente (che è @Transactional),
     * così un record malformato finisce in T_BATCH_CARICAMENTO_ERRORE senza fare
     * rollback dei record già scritti correttamente nello stesso batch.
     */
    public BatchEsitoResponse carica(List<RiderAnagraficaDto> lista, String nomeFileOrigine, String checksum, String clientId) {
        long idBatch = batchRepository.creaBatch(TipoEntita.ANAGRAFICA, TipoOperazione.CARICAMENTO, null,
                null, nomeFileOrigine, "JSON", clientId, checksum);
        int ok = 0, ko = 0;
        for (RiderAnagraficaDto dto : lista) {
            try {
                caricaSingolo(dto, idBatch);
                ok++;
            } catch (Exception e) {
                ko++;
                log.error("Batch {}: caricamento fallito per il rider {}", idBatch, dto.idRider(), e);
                batchRepository.registraErrore(idBatch, dto.idRider(), messaggioCompleto(e), dto.toString());
            }
        }
        EsitoBatch esito = ko == 0 ? EsitoBatch.OK : (ok == 0 ? EsitoBatch.KO : EsitoBatch.PARZIALE);
        batchRepository.chiudiBatch(idBatch, esito, lista.size(), ok, ko);
        return new BatchEsitoResponse(idBatch, TipoOperazione.CARICAMENTO, esito, lista.size(), ok, ko, Instant.now());
    }

    private void caricaSingolo(RiderAnagraficaDto dto, long idBatch) {
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
