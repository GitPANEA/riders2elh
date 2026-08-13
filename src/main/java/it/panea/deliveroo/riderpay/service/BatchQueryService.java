package it.panea.deliveroo.riderpay.service;

import it.panea.deliveroo.riderpay.common.*;
import it.panea.deliveroo.riderpay.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riderpay.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class BatchQueryService {

    private final BatchCaricamentoRepository batchRepository;
    private final RiderAnagraficaRepository anagraficaRepository;
    private final VoceRepository voceRepository;
    private final MovimentazioneRepository movimentazioneRepository;

    public BatchQueryService(BatchCaricamentoRepository batchRepository, RiderAnagraficaRepository anagraficaRepository,
                              VoceRepository voceRepository, MovimentazioneRepository movimentazioneRepository) {
        this.batchRepository = batchRepository;
        this.anagraficaRepository = anagraficaRepository;
        this.voceRepository = voceRepository;
        this.movimentazioneRepository = movimentazioneRepository;
    }

    public BatchRow leggi(long idBatch) {
        return batchRepository.trovaPerId(idBatch)
                .orElseThrow(() -> new RisorsaNonTrovataException("Batch non trovato: " + idBatch));
    }

    public List<BatchRow> elenca(TipoOperazione tipoOperazione) {
        return batchRepository.elenca(tipoOperazione);
    }

    /** § 9.3.1 punto 4: annullamento in blocco di un intero batch (es. file caricato per errore). */
    @Transactional
    public AnnullamentoBatchResponse annullaBatch(long idBatchOriginale, String motivo, String clientId) {
        BatchRow batchOriginale = leggi(idBatchOriginale);

        long idBatchAnnullamento = batchRepository.creaBatch(batchOriginale.tipoEntita(), TipoOperazione.ANNULLAMENTO,
                idBatchOriginale, motivo, null, null, clientId, null);

        int annullati = switch (batchOriginale.tipoEntita()) {
            case ANAGRAFICA -> {
                List<String> chiavi = anagraficaRepository.trovaChiaviCorrentiPerBatch(idBatchOriginale);
                chiavi.forEach(idRider -> {
                    var corrente = anagraficaRepository.trovaVersioneCorrente(idRider).orElseThrow();
                    anagraficaRepository.sostituisciVersioneCorrente(corrente.dati(), idBatchAnnullamento, StatoRecord.ANNULLATO);
                });
                yield chiavi.size();
            }
            case VOCE -> {
                List<String> chiavi = voceRepository.trovaChiaviCorrentiPerBatch(idBatchOriginale);
                chiavi.forEach(idVoce -> {
                    var corrente = voceRepository.trovaVersioneCorrente(idVoce).orElseThrow();
                    voceRepository.sostituisciVersioneCorrente(corrente.dati(), idBatchAnnullamento, StatoRecord.ANNULLATO);
                });
                yield chiavi.size();
            }
            case MOVIMENTAZIONE -> {
                List<String> chiavi = movimentazioneRepository.trovaChiaviCorrentiPerBatch(idBatchOriginale);
                chiavi.forEach(idMovimentazione -> {
                    var corrente = movimentazioneRepository.trovaVersioneCorrente(idMovimentazione).orElseThrow();
                    movimentazioneRepository.sostituisciVersioneCorrente(corrente.dati(), idBatchAnnullamento, StatoRecord.ANNULLATO);
                });
                yield chiavi.size();
            }
        };

        batchRepository.chiudiBatch(idBatchAnnullamento, EsitoBatch.OK, annullati, annullati, 0);
        return new AnnullamentoBatchResponse(idBatchAnnullamento, idBatchOriginale, annullati, Instant.now());
    }
}
