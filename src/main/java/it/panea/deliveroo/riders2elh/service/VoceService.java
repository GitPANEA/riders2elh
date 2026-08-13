package it.panea.deliveroo.riders2elh.service;

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

    public VoceService(VoceRepository repository, MasterKeyRepository masterKeyRepository,
                        BatchCaricamentoRepository batchRepository) {
        this.repository = repository;
        this.masterKeyRepository = masterKeyRepository;
        this.batchRepository = batchRepository;
    }

    public BatchEsitoResponse carica(List<VoceDto> lista, String nomeFileOrigine, String checksum, String clientId) {
        long idBatch = batchRepository.creaBatch(TipoEntita.VOCE, TipoOperazione.CARICAMENTO, null,
                null, nomeFileOrigine, "CSV", clientId, checksum);
        int ok = 0, ko = 0;
        for (VoceDto dto : lista) {
            try {
                caricaSingola(dto, idBatch);
                ok++;
            } catch (Exception e) {
                ko++;
                log.error("Batch {}: caricamento fallito per la voce {}", idBatch, dto.idVoce(), e);
                batchRepository.registraErrore(idBatch, dto.idVoce(), messaggioCompleto(e), dto.toString());
            }
        }
        EsitoBatch esito = ko == 0 ? EsitoBatch.OK : (ok == 0 ? EsitoBatch.KO : EsitoBatch.PARZIALE);
        batchRepository.chiudiBatch(idBatch, esito, lista.size(), ok, ko);
        return new BatchEsitoResponse(idBatch, TipoOperazione.CARICAMENTO, esito, lista.size(), ok, ko, Instant.now());
    }

    private void caricaSingola(VoceDto dto, long idBatch) {
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
