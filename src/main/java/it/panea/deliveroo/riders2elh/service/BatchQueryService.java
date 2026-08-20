package it.panea.deliveroo.riders2elh.service;

import it.panea.deliveroo.riders2elh.common.*;
import it.panea.deliveroo.riders2elh.dto.AnnullamentoBatchResponse;
import it.panea.deliveroo.riders2elh.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private final Duration sogliaBloccato;

    public BatchQueryService(BatchCaricamentoRepository batchRepository, RiderAnagraficaRepository anagraficaRepository,
                              VoceRepository voceRepository, MovimentazioneRepository movimentazioneRepository,
                              @Value("${riders2eLH.batch.soglia-bloccato-minuti:30}") long sogliaBloccatoMinuti) {
        this.batchRepository = batchRepository;
        this.anagraficaRepository = anagraficaRepository;
        this.voceRepository = voceRepository;
        this.movimentazioneRepository = movimentazioneRepository;
        this.sogliaBloccato = Duration.ofMinutes(sogliaBloccatoMinuti);
    }

    public BatchRow leggi(long idBatch) {
        BatchRow batch = batchRepository.trovaPerId(idBatch)
                .orElseThrow(() -> new RisorsaNonTrovataException("Batch non trovato: " + idBatch));
        return conProbabileBlocco(batch);
    }

    /**
     * Segnala, solo in lettura (nessuna scrittura sulla riga), un batch IN_CORSO da più
     * della soglia configurata: può indicare un task asincrono interrotto senza che il
     * catch in elaboraAsync sia riuscito a chiudere il batch (es. connessione persa anche
     * nel tentativo di marcarlo ERRORE_TECNICO — vedi commento in AnagraficaService).
     */
    private BatchRow conProbabileBlocco(BatchRow batch) {
        boolean bloccato = batch.esito() == EsitoBatch.IN_CORSO
                && batch.dtInizioElaborazione() != null
                && Duration.between(batch.dtInizioElaborazione(), Instant.now()).compareTo(sogliaBloccato) > 0;
        if (!bloccato) {
            return batch;
        }
        return new BatchRow(batch.idBatch(), batch.tipoEntita(), batch.tipoOperazione(), batch.idBatchRiferimento(),
                batch.motivoOperazione(), batch.nomeFileOrigine(), batch.clientId(), batch.dtRicezione(),
                batch.dtInizioElaborazione(), batch.dtFineElaborazione(), batch.esito(), batch.numRecordTotali(),
                batch.numRecordOk(), batch.numRecordKo(), true);
    }

    /** GET /api/v1/batch/{idBatch}/errori — 404 se il batch non esiste, 200 con lista anche vuota altrimenti. */
    public List<BatchErroreRow> elencaErrori(long idBatch, int page, int size) {
        leggi(idBatch);
        if (page < 0 || size < 1) {
            throw new RichiestaNonValidaException("page deve essere >= 0 e size deve essere >= 1.");
        }
        return batchRepository.elencaErrori(idBatch, page, size);
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
        return batchRepository.elenca(operazione, dataInizio, dataFine).stream()
                .map(this::conProbabileBlocco).toList();
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
