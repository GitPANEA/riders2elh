package it.panea.deliveroo.riders2elh.service;

import it.panea.deliveroo.riders2elh.common.*;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riders2elh.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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

    /**
     * Elenco con filtri opzionali e combinabili. Le due date vanno passate insieme: una sola
     * sarebbe un intervallo aperto, che non è la semantica dell'endpoint — meglio un errore
     * esplicito che una lista silenziosamente diversa da quella attesa.
     * <p>
     * {@code tipoOperazione} arriva come {@code String} e non come enum: vedi
     * {@link #convertiTipoOperazione(String)} per il perché.
     */
    public List<BatchRow> elenca(String tipoOperazione, LocalDate dataInizio, LocalDate dataFine) {
        TipoOperazione operazione = convertiTipoOperazione(tipoOperazione);
        if ((dataInizio == null) != (dataFine == null)) {
            throw new RichiestaNonValidaException(
                    "I parametri dataInizio e dataFine vanno specificati entrambi oppure nessuno dei due.");
        }
        if (dataInizio != null && dataInizio.isAfter(dataFine)) {
            throw new RichiestaNonValidaException(
                    "dataInizio (" + dataInizio + ") è successiva a dataFine (" + dataFine + ").");
        }
        return batchRepository.elenca(operazione, dataInizio, dataFine);
    }

    /**
     * Normalizza il valore di {@code tipoOperazione} ricevuto dalla query string: accetta
     * qualunque combinazione di maiuscole/minuscole e spazi accidentali, e su un valore non
     * riconosciuto solleva un 400 che elenca quelli ammessi (senza, sarebbe un 500 opaco).
     * <p>
     * {@code Locale.ROOT} e non il locale di default: i valori dell'enum sono identificatori
     * ASCII, non testo in lingua, e la conversione va fatta con regole invarianti — con il
     * locale di default, una JVM avviata con {@code -Duser.language=tr} maiuscolizzerebbe
     * {@code i} in {@code İ}, e {@code rettifica} non corrisponderebbe più a {@code RETTIFICA}.
     */
    private TipoOperazione convertiTipoOperazione(String valore) {
        if (valore == null || valore.isBlank()) {
            return null;
        }
        try {
            return TipoOperazione.valueOf(valore.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new RichiestaNonValidaException("tipoOperazione non valido: '" + valore
                    + "'. Valori ammessi: " + Arrays.stream(TipoOperazione.values())
                    .map(Enum::name).collect(Collectors.joining(", ")) + ".");
        }
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
