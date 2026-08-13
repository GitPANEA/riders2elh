package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.MovimentazioneDto;

import java.time.Instant;

public record MovimentazioneRow(
        long idMovimentazioneSt,
        MovimentazioneDto dati,
        long idBatchCaricamento,
        Instant dtInserimento,
        StatoRecord statoRecord
) {}
