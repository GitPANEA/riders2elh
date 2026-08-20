package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.EsitoBatch;
import it.panea.deliveroo.riders2elh.common.TipoEntita;
import it.panea.deliveroo.riders2elh.common.TipoOperazione;

import java.time.Instant;

public record BatchRow(
        long idBatch,
        TipoEntita tipoEntita,
        TipoOperazione tipoOperazione,
        Long idBatchRiferimento,
        String motivoOperazione,
        String nomeFileOrigine,
        String clientId,
        Instant dtRicezione,
        Instant dtInizioElaborazione,
        Instant dtFineElaborazione,
        EsitoBatch esito,
        Integer numRecordTotali,
        Integer numRecordOk,
        Integer numRecordKo,
        /**
         * Calcolato in lettura (non da colonna): true se il batch è IN_CORSO da più
         * tempo della soglia configurata — segnale di possibile blocco del task
         * asincrono, senza bisogno di scrivere nulla sulla riga per rilevarlo.
         */
        boolean probabilmenteBloccato
) {}
