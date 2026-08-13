package it.panea.deliveroo.riders2elh.dto;

import it.panea.deliveroo.riders2elh.common.EsitoBatch;
import it.panea.deliveroo.riders2elh.common.TipoOperazione;

import java.time.Instant;

public record BatchEsitoResponse(
        long idBatch,
        TipoOperazione tipoOperazione,
        EsitoBatch esito,
        int recordTotali,
        int recordOk,
        int recordKo,
        Instant dtRicezione
) {}
