package it.panea.deliveroo.riders2elh.repository;

import java.time.Instant;

public record BatchErroreRow(
        long idBatchErrore,
        long idBatch,
        Integer numeroRiga,
        String chiaveBusiness,
        String messaggioErrore,
        String payloadJson,
        Instant dtInserimento
) {}
