package it.panea.deliveroo.riders2elh.common;

import it.panea.deliveroo.riders2elh.dto.MovimentazioneDto;
import it.panea.deliveroo.riders2elh.dto.RiderAnagraficaDto;
import it.panea.deliveroo.riders2elh.dto.VoceDto;
import it.panea.deliveroo.riders2elh.dto.VoceMovimentazioneDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validazione di formato/coerenza per singolo record, invocata dentro il ciclo dei
 * service (non con @Valid sulla lista nel controller: un solo record anomalo non deve
 * respingere l'intero batch, deve solo finire come suo KO isolato — vedi commento in
 * RiderAnagraficaDto). Ogni metodo ritorna la lista delle violazioni, vuota se il
 * record è valido; non lancia eccezioni, decide il chiamante.
 */
public final class ValidatoreFormato {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CAP = Pattern.compile("^[0-9]{5}$");
    private static final Pattern TELEFONO = Pattern.compile("^\\+?[0-9 ()-]{6,20}$");
    private static final Pattern TELEFONO_CIFRE = Pattern.compile("[0-9]");
    private static final Pattern CODICE_FISCALE_FORMATO =
            Pattern.compile("^[A-Z]{6}[0-9]{2}[A-EHLMPR-T][0-9]{2}[A-Z][0-9]{3}[A-Z]$");
    // Formato yyyy-mm: allineato al commento di colonna e al CHECK Oracle
    // CK_MOVVOCE_MESE_RIFERIMENTO su T_MOVIMENTAZIONE_VOCE_ST.MESE_RIFERIMENTO
    // (docs/db/05-ddl-check-coerenza.sql). Il pattern precedente (mm-yyyy) era invertito
    // rispetto al DB fin dall'introduzione della validazione: un valore corretto secondo
    // lo schema (es. "2026-06") veniva respinto dall'applicazione prima di raggiungere Oracle.
    private static final Pattern MESE_RIFERIMENTO = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    // Algoritmo standard del carattere di controllo del codice fiscale italiano:
    // valore convenzionale di ogni carattere in base alla posizione (pari/dispari,
    // 1-based) e resto della somma modulo 26, mappato sulla lettera di controllo.
    private static final Map<Character, Integer> VALORI_DISPARI = valoriDispari();
    private static final Map<Character, Integer> VALORI_PARI = valoriPari();
    private static final char[] RESTO_A_LETTERA = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private ValidatoreFormato() {
    }

    public static List<String> validaAnagrafica(RiderAnagraficaDto dto) {
        List<String> violazioni = new ArrayList<>();
        if (!isBlank(dto.email()) && !EMAIL.matcher(dto.email()).matches()) {
            violazioni.add("email non valida: " + dto.email());
        }
        if (!isBlank(dto.telefonoCellulare()) && !telefonoPlausibile(dto.telefonoCellulare())) {
            violazioni.add("telefono_cellulare non valido: " + dto.telefonoCellulare());
        }
        if (!isBlank(dto.capResidenza()) && !CAP.matcher(dto.capResidenza()).matches()) {
            violazioni.add("cap_residenza non valido, atteso 5 cifre: " + dto.capResidenza());
        }
        // Controllo formato/carattere di controllo del CF temporaneamente disabilitato:
        // il cliente sta testando con dati non reali (CF non conformi per costruzione).
        // Ripristinare togliendo il commento prima di dati reali.
        // if (!isBlank(dto.codiceFiscale()) && !codiceFiscaleValido(dto.codiceFiscale())) {
        //     violazioni.add("codice_fiscale non valido: " + dto.codiceFiscale());
        // }
        return violazioni;
    }

    public static List<String> validaMovimentazione(MovimentazioneDto dto) {
        List<String> violazioni = new ArrayList<>();
        if (dto.periodoDa() != null && dto.periodoA() != null && dto.periodoDa().isAfter(dto.periodoA())) {
            violazioni.add("periodo_da (" + dto.periodoDa() + ") successivo a periodo_a (" + dto.periodoA() + ")");
        }
        validaMeseRiferimento(dto.modificheIntegrazioni(), violazioni);
        validaMeseRiferimento(dto.prospettoFinale(), violazioni);
        return violazioni;
    }

    public static List<String> validaVoce(VoceDto dto) {
        return List.of();
    }

    private static void validaMeseRiferimento(List<VoceMovimentazioneDto> voci, List<String> violazioni) {
        if (voci == null) {
            return;
        }
        for (VoceMovimentazioneDto voce : voci) {
            String mese = voce.meseRiferimento();
            if (!isBlank(mese) && !MESE_RIFERIMENTO.matcher(mese).matches()) {
                violazioni.add("mese_riferimento non valido, atteso yyyy-mm: " + mese);
            }
        }
    }

    private static boolean telefonoPlausibile(String telefono) {
        if (!TELEFONO.matcher(telefono).matches()) {
            return false;
        }
        return TELEFONO_CIFRE.matcher(telefono).results().count() >= 6;
    }

    private static boolean codiceFiscaleValido(String cf) {
        String cfMaiuscolo = cf.toUpperCase();
        if (!CODICE_FISCALE_FORMATO.matcher(cfMaiuscolo).matches()) {
            return false;
        }
        int somma = 0;
        for (int posizione = 0; posizione < 15; posizione++) {
            char carattere = cfMaiuscolo.charAt(posizione);
            boolean posizioneDispari = (posizione % 2) == 0; // 1-based: indice 0 -> posizione 1, dispari
            somma += posizioneDispari ? VALORI_DISPARI.get(carattere) : VALORI_PARI.get(carattere);
        }
        char attesa = RESTO_A_LETTERA[somma % 26];
        return cfMaiuscolo.charAt(15) == attesa;
    }

    private static boolean isBlank(String valore) {
        return valore == null || valore.isBlank();
    }

    private static Map<Character, Integer> valoriDispari() {
        Map<Character, Integer> m = new java.util.HashMap<>();
        String lettere = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] valori = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21, 2, 4, 18, 20, 11, 3, 6, 8, 12, 14, 16, 10, 22, 25, 24, 23};
        for (int i = 0; i < lettere.length(); i++) {
            m.put(lettere.charAt(i), valori[i]);
        }
        String cifre = "0123456789";
        int[] valoriCifre = {1, 0, 5, 7, 9, 13, 15, 17, 19, 21};
        for (int i = 0; i < cifre.length(); i++) {
            m.put(cifre.charAt(i), valoriCifre[i]);
        }
        return Map.copyOf(m);
    }

    private static Map<Character, Integer> valoriPari() {
        Map<Character, Integer> m = new java.util.HashMap<>();
        String lettere = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 0; i < lettere.length(); i++) {
            m.put(lettere.charAt(i), i);
        }
        String cifre = "0123456789";
        for (int i = 0; i < cifre.length(); i++) {
            m.put(cifre.charAt(i), i);
        }
        return Map.copyOf(m);
    }
}
