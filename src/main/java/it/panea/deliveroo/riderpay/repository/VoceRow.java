package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.VoceDto;

import java.time.Instant;

public record VoceRow(long idVoceSt, VoceDto dati, long idBatchCaricamento, Instant dtInserimento, StatoRecord statoRecord) {}
