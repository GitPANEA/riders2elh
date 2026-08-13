package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;

import java.math.BigDecimal;
import java.time.Instant;

/** Proiezione leggera per GET .../storico (§ 10): non serve il dettaglio completo. */
public record MovimentazioneVersioneSintesi(
        long idMovimentazioneSt,
        Instant dtInserimento,
        BigDecimal totaleDovuto,
        long idBatchCaricamento,
        StatoRecord statoRecord
) {}
