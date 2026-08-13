package it.panea.deliveroo.riderpay.dto;

import it.panea.deliveroo.riderpay.common.EsitoBatch;
import it.panea.deliveroo.riderpay.common.TipoOperazione;

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
