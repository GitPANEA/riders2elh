package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.RiderAnagraficaDto;

import java.time.Instant;

public record RiderAnagraficaRow(
        long idAnagraficaSt,
        RiderAnagraficaDto dati,
        long idBatchCaricamento,
        Instant dtInserimento,
        StatoRecord statoRecord
) {}
