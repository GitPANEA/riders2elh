package it.panea.deliveroo.riderpay.dto;

import it.panea.deliveroo.riderpay.common.TipoOperazione;

import java.time.Instant;

public record AnnullamentoResponse(
        long idBatch,
        TipoOperazione tipoOperazione,
        String chiaveBusiness,
        Instant dtInserimento
) {}
