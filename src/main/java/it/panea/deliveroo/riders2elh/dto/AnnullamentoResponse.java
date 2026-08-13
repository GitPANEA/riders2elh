package it.panea.deliveroo.riders2elh.dto;

import it.panea.deliveroo.riders2elh.common.TipoOperazione;

import java.time.Instant;

public record AnnullamentoResponse(
        long idBatch,
        TipoOperazione tipoOperazione,
        String chiaveBusiness,
        Instant dtInserimento
) {}
