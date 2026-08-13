package it.panea.deliveroo.riderpay.dto;

import java.time.Instant;

public record AnnullamentoBatchResponse(
        long idBatchAnnullamento,
        long idBatchOriginale,
        int recordAnnullati,
        Instant dtAnnullamento
) {}
