package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.EsitoBatch;
import it.panea.deliveroo.riderpay.common.TipoEntita;
import it.panea.deliveroo.riderpay.common.TipoOperazione;

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
        Instant dtFineElaborazione,
        EsitoBatch esito,
        Integer numRecordTotali,
        Integer numRecordOk,
        Integer numRecordKo
) {}
