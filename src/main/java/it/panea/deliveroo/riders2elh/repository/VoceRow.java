package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.StatoRecord;
import it.panea.deliveroo.riders2elh.dto.VoceDto;

import java.time.Instant;

public record VoceRow(long idVoceSt, VoceDto dati, long idBatchCaricamento, Instant dtInserimento, StatoRecord statoRecord) {}
