package it.panea.deliveroo.riderpay.repository;

import it.panea.deliveroo.riderpay.common.StatoRecord;
import it.panea.deliveroo.riderpay.dto.RiepilogoDto;
import it.panea.deliveroo.riderpay.dto.TotaliConsegneDto;
import it.panea.deliveroo.riderpay.dto.TotaliVoceDto;

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
