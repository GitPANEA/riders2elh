package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.StatoRecord;
import it.panea.deliveroo.riders2elh.dto.MovimentazioneDto;

import java.time.Instant;

public record MovimentazioneRow(
        long idMovimentazioneSt,
        MovimentazioneDto dati,
        long idBatchCaricamento,
        Instant dtInserimento,
        StatoRecord statoRecord
) {}
