package it.panea.deliveroo.riders2elh.dto;

import java.time.Instant;

public record AnnullamentoBatchResponse(
        long idBatchAnnullamento,
        long idBatchOriginale,
        int recordAnnullati,
        Instant dtAnnullamento
) {}
