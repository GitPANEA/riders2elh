package it.panea.deliveroo.riders2elh.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.panea.deliveroo.riders2elh.common.*;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoResponse;
import it.panea.deliveroo.riders2elh.dto.BatchEsitoResponse;
import it.panea.deliveroo.riders2elh.dto.MovimentazioneDto;
import it.panea.deliveroo.riders2elh.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static it.panea.deliveroo.riders2elh.common.DiagnosticaErrori.messaggioCompleto;

@Service
public class MovimentazioneService {

    private static final Logger log = LoggerFactory.getLogger(MovimentazioneService.class);

    private final MovimentazioneRepository repository;
    private final MasterKeyRepository masterKeyRepository;
    private final BatchCaricamentoRepository batchRepository;
    private final ObjectMapper objectMapper;
    private final int intervalloProgresso;

    public MovimentazioneService(MovimentazioneRepository repository, MasterKeyRepository masterKeyRepository,
                                  BatchCaricamentoRepository batchRepository, ObjectMapper objectMapper,
                                  @Value("${riders2eLH.batch.intervallo-progresso:1000}") int intervalloProgresso) {
        this.repository = repository;
        this.masterKeyRepository = masterKeyRepository;
        this.batchRepository = batchRepository;
        this.objectMapper = objectMapper;
        this.intervalloProgresso = intervalloProgresso;
    }

    /**
     * Avvio: crea il batch e ritorna subito. Il controller risponde 202 con l'id batch
     * e affida l'elaborazione a {@link #elaboraAsync} (invocata dal controller, non da
     * qui: una self-invocation non passerebbe dal proxy Spring che rende @Async effettivo).
     */
    public long avviaCaricamento(List<MovimentazioneDto> lista, String nomeFileOrigine, String checksum, String clientId) {
        return batchRepository.creaBatch(TipoEntita.MOVIMENTAZIONE, TipoOperazione.CARICAMENTO, null,
                null, nomeFileOrigine, "JSON", clientId, checksum);
    }

    @Async("batchTaskExecutor")
    public void elaboraAsync(long idBatch, List<MovimentazioneDto> lista) {
        int ok = 0, ko = 0;
        try {
            batchRepository.avviaElaborazione(idBatch, lista.size());
            for (int i = 0; i < lista.size(); i++) {
                MovimentazioneDto dto = lista.get(i);
                try {
                    caricaSingola(dto, idBatch);
                    ok++;
                } catch (Exception e) {
                    ko++;
                    log.error("Batch {}: caricamento fallito per la movimentazione {}", idBatch, dto.idMovimentazione(), e);
                    batchRepository.registraErrore(idBatch, i, dto.idMovimentazione(), messaggioCompleto(e), payloadJson(dto));
                }
                if ((ok + ko) % intervalloProgresso == 0) {
                    batchRepository.aggiornaProgresso(idBatch, ok, ko);
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

    private String payloadJson(MovimentazioneDto dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (Exception e) {
            return dto.toString();
        }
    }

    private void caricaSingola(MovimentazioneDto dto, long idBatch) {
        List<String> violazioni = ValidatoreFormato.validaMovimentazione(dto);
        if (!violazioni.isEmpty()) {
            throw new RecordNonValidoException(violazioni);
        }
        masterKeyRepository.assicuraRider(dto.idRider());
        dto.modificheIntegrazioni().forEach(v -> masterKeyRepository.assicuraVoce(v.idVoce()));
        dto.prospettoFinale().forEach(v -> masterKeyRepository.assicuraVoce(v.idVoce()));

        var correnteEsistente = repository.trovaVersioneCorrente(dto.idMovimentazione());
        if (correnteEsistente.isPresent() && correnteEsistente.get().dati().equals(dto)) {
            return;
        }
        repository.sostituisciVersioneCorrente(dto, idBatch, StatoRecord.ATTIVO);
    }

    public MovimentazioneRow leggiCorrente(String idMovimentazione) {
        return repository.trovaVersioneCorrente(idMovimentazione)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna movimentazione corrente: " + idMovimentazione));
    }

    public List<MovimentazioneVersioneSintesi> leggiStorico(String idMovimentazione) {
        return repository.elencaStorico(idMovimentazione);
    }

    public List<MovimentazioneHeaderRow> leggiCorrentiPerRider(String idRider, LocalDate periodoDa, LocalDate periodoA) {
        return repository.elencaCorrentiPerRider(idRider, periodoDa, periodoA);
    }

    /** § 9.3: DELETE — annullamento logico; nessuna riga di dettaglio per la versione annullata. */
    @Transactional
    public AnnullamentoResponse annulla(String idMovimentazione, String motivo, String clientId) {
        MovimentazioneRow corrente = repository.trovaVersioneCorrente(idMovimentazione)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna movimentazione corrente da annullare: " + idMovimentazione));
        long idBatch = batchRepository.creaBatch(TipoEntita.MOVIMENTAZIONE, TipoOperazione.ANNULLAMENTO,
                corrente.idBatchCaricamento(), motivo, null, null, clientId, null);
        try {
            repository.sostituisciVersioneCorrente(corrente.dati(), idBatch, StatoRecord.ANNULLATO);
        } catch (DataIntegrityViolationException e) {
            throw new ConflittoConcorrenzaException(
                    "Un'altra richiesta ha modificato la movimentazione " + idMovimentazione + " in concorrenza", e);
        }
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new AnnullamentoResponse(idBatch, TipoOperazione.ANNULLAMENTO, idMovimentazione, Instant.now());
    }

    /** § 9.3: rettifica esplicita — invia un prospetto corretto motivato. */
    @Transactional
    public BatchEsitoResponse rettifica(String idMovimentazione, MovimentazioneDto datiCorretti, String motivo, String clientId) {
        MovimentazioneRow correnteErrata = repository.trovaVersioneCorrente(idMovimentazione)
                .orElseThrow(() -> new RisorsaNonTrovataException("Nessuna movimentazione corrente da rettificare: " + idMovimentazione));
        long idBatch = batchRepository.creaBatch(TipoEntita.MOVIMENTAZIONE, TipoOperazione.RETTIFICA,
                correnteErrata.idBatchCaricamento(), motivo, null, null, clientId, null);
        repository.sostituisciVersioneCorrente(datiCorretti, idBatch, StatoRecord.ATTIVO);
        batchRepository.chiudiBatch(idBatch, EsitoBatch.OK, 1, 1, 0);
        return new BatchEsitoResponse(idBatch, TipoOperazione.RETTIFICA, EsitoBatch.OK, 1, 1, 0, Instant.now());
    }
}
