package it.panea.deliveroo.riders2elh.repository;

import it.panea.deliveroo.riders2elh.common.StatoRecord;
import it.panea.deliveroo.riders2elh.dto.RiepilogoDto;
import it.panea.deliveroo.riders2elh.dto.TotaliConsegneDto;
import it.panea.deliveroo.riders2elh.dto.TotaliVoceDto;

import java.time.Instant;
import java.time.LocalDate;

public record MovimentazioneHeaderRow(
        long idMovimentazioneSt,
        String idMovimentazione,
        String idRider,
        LocalDate periodoDa,
        LocalDate periodoA,
        TotaliConsegneDto totaliConsegne,
        TotaliVoceDto totaliModificheIntegrazioni,
        RiepilogoDto riepilogo,
        long idBatchCaricamento,
        Instant dtInserimento,
        StatoRecord statoRecord
) {}
