package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.StatoRecord;
import it.panea.deliveroo.riders2elh.dto.RiderAnagraficaDto;

import java.time.Instant;

public record RiderAnagraficaRow(
        long idAnagraficaSt,
        RiderAnagraficaDto dati,
        long idBatchCaricamento,
        Instant dtInserimento,
        StatoRecord statoRecord
) {}
