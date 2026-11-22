package com.pixeldweller.maskerade.veil;

import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.Hashes;
import com.pixeldweller.maskerade.util.Words;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Suggests placeholders from built-in, made-up word lists — deterministically from a per-project seed, so the
 * same term always gets the same placeholder in the same project. A suggestion that is "taken" (occurs in the
 * original, is used by another term, or is a prefix of one) is skipped and the next one is tried.
 */
public final class PlaceholderGenerator {

    private static final String[] FIRST_NAMES = {
            "Alma", "Bendix", "Cora", "Detlev", "Elske", "Folke", "Gesa", "Hauke", "Ilka", "Jannes", "Kerrin", "Lasse",
            "Maren", "Nele", "Ommo", "Piet", "Rieke", "Swantje", "Tjark", "Ubbo", "Vanja", "Wiebke", "Arvid", "Birte",
            "Carlotta", "Dorian", "Enno", "Frauke", "Gunnar", "Hilke", "Imke", "Jelto", "Keno", "Liska", "Merle", "Nils",
            "Okko", "Pelle", "Ronja", "Silas", "Talea", "Ulrike", "Volker", "Wanda", "Yara", "Zeno"};

    private static final String[] LAST_NAMES = {
            "Ahlers", "Bruns", "Carstens", "Dirksen", "Eggers", "Frerichs", "Gerdes", "Harms", "Iwersen", "Janssen",
            "Kruse", "Lorenzen", "Martens", "Nissen", "Ottens", "Peters", "Rickmers", "Siebels", "Thiessen", "Ulpts",
            "Voss", "Wilkens", "Albrecht", "Behrens", "Clausen", "Dreyer", "Engelke", "Fokken", "Grotjahn", "Hinrichs",
            "Ihnen", "Jacobsen", "Kloppenburg", "Lammers", "Meiners", "Niemeyer", "Oltmanns", "Poppinga", "Reents",
            "Saathoff", "Tammen", "Uphoff", "Vogts", "Weerts"};

    private static final String[] COMPANY_STEMS = {
            "Seewald", "Lindgrund", "Birkenhof", "Elbkant", "Moorbach", "Haffkamp", "Dornquell", "Ebbstrom", "Kiefernau",
            "Nordwind", "Sandwerder", "Tidenhub", "Uferlicht", "Wattkant", "Ostmarsch", "Rotklee", "Brackwasser",
            "Fjordlund", "Geestrand", "Heidkoppel", "Inselgrund", "Kliffhaus", "Lotsenhof", "Muschelbank", "Priel"};

    private static final String[] COMPANY_WORDS = {
            "Handel", "Spedition", "Dienste", "Werke", "Systeme", "Partner", "Gruppe", "Kontor", "Betrieb", "Union",
            "Holding", "Technik", "Versand", "Verbund", "Projekt", "Media", "Consult"};

    private static final String[] CODENAMES = {
            "Reiher", "Basalt", "Quarz", "Ahorn", "Kiesel", "Granit", "Bernstein", "Wacholder", "Tanne", "Holunder",
            "Sanddorn", "Schlehe", "Feldspat", "Glimmer", "Erle", "Ginster", "Kormoran", "Austernfischer", "Brachvogel",
            "Seeschwalbe", "Strandhafer", "Queller", "Dolomit", "Obsidian", "Porphyr", "Tuff", "Malachit", "Rotdorn"};

    private static final String[] PRODUCTS = {
            "Vela", "Lumen", "Kappa", "Nimbus", "Orbit", "Prisma", "Quanta", "Radix", "Sigma", "Tessera", "Umbra",
            "Vertex", "Zenit", "Aurum", "Borea", "Cirrus", "Delta", "Ferro", "Gamma", "Helix"};

    private final String seed;

    public PlaceholderGenerator(String seed) {
        this.seed = seed;
    }

    /**
     * @param taken true for suggestions that must not be used (already in the original, used by another term …)
     */
    public String suggest(TermKind kind, String real, Predicate<String> taken) {
        var wordCount = Math.max(1, Words.letterParts(real).size());
        for (int attempt = 0; attempt < 500; attempt++) {
            var candidate = candidate(kind, real, wordCount, attempt);
            if (!taken.test(candidate)) return candidate;
        }
        // lists exhausted: fall back to a numbered placeholder (still deterministic)
        return kind.literal() ? "item" + Math.floorMod(index(kind, real, 0), 100_000) + ".example" : "Veiled";
    }

    private String candidate(TermKind kind, String real, int wordCount, int attempt) {
        return switch (kind) {
            case PERSON, CUSTOMER -> person(real, wordCount, attempt);
            case COMPANY -> company(real, wordCount, attempt);
            // IBANs, phone numbers and other values with digits keep their shape
            case PROJECT, OTHER -> real.chars().anyMatch(Character::isDigit) ? secret(real, attempt) : words(CODENAMES, real, wordCount, attempt);
            case PRODUCT -> words(PRODUCTS, real, wordCount, attempt);
            case USERNAME -> username(real, attempt);
            case MAIL -> mail(real, attempt);
            case HOST -> host(real, attempt);
            case SECRET -> secret(real, attempt);
        };
    }

    private String person(String real, int wordCount, int attempt) {
        if (wordCount == 1) return pick(LAST_NAMES, real, attempt, 0);
        var words = new ArrayList<String>();
        for (int i = 0; i < wordCount - 1; i++) words.add(pick(FIRST_NAMES, real, attempt, i));
        words.add(pick(LAST_NAMES, real, attempt, wordCount));
        return String.join(" ", words);
    }

    private String company(String real, int wordCount, int attempt) {
        var words = new ArrayList<String>();
        words.add(pick(COMPANY_STEMS, real, attempt, 0));
        for (int i = 1; i < wordCount; i++) words.add(pick(COMPANY_WORDS, real, attempt, i));
        return String.join(" ", words);
    }

    private String words(String[] list, String real, int wordCount, int attempt) {
        var words = new ArrayList<String>();
        for (int i = 0; i < wordCount; i++) words.add(pick(list, real, attempt, i));
        return String.join(" ", words);
    }

    private String username(String real, int attempt) {
        var first = pick(FIRST_NAMES, real, attempt, 0).toLowerCase(Locale.ROOT);
        var last = pick(LAST_NAMES, real, attempt, 1).toLowerCase(Locale.ROOT);
        return first.charAt(0) + last;
    }

    private String mail(String real, int attempt) {
        var first = pick(FIRST_NAMES, real, attempt, 0).toLowerCase(Locale.ROOT);
        var last = pick(LAST_NAMES, real, attempt, 1).toLowerCase(Locale.ROOT);
        return (real.matches("^[^@]\\..*") ? first.charAt(0) : first) + "." + last + "@example.org";
    }

    private String host(String real, int attempt) {
        var lower = real.toLowerCase(Locale.ROOT);
        var stem = pick(CODENAMES, real, attempt, 0).toLowerCase(Locale.ROOT);
        int n = Math.floorMod(index(TermKind.HOST, real, attempt), 200) + 10;
        if (lower.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            // documentation ranges (RFC 5737)
            var nets = new String[]{"192.0.2.", "198.51.100.", "203.0.113."};
            return nets[Math.floorMod(index(TermKind.HOST, real, attempt + 7), 3)] + n;
        }
        if (lower.startsWith("jdbc:")) {
            var scheme = lower.replaceAll("^(jdbc:[a-z0-9]+:(//)?).*", "$1");
            return scheme + "db" + n + "." + stem + ".example/" + stem;
        }
        if (lower.contains("://")) {
            var scheme = lower.substring(0, lower.indexOf("://") + 3);
            return scheme + stem + ".example";
        }
        return "host" + n + "." + stem + ".example";
    }

    /** Same length and shape: letters stay letters (same case), digits stay digits, symbols stay. */
    private String secret(String real, int attempt) {
        var sb = new StringBuilder(real.length());
        for (int i = 0; i < real.length(); i++) {
            char c = real.charAt(i);
            long r = Math.floorMod(Hashes.sha256Long(seed + "|secret|" + real + "|" + attempt + "|" + i), 1L << 30);
            if (c >= 'A' && c <= 'Z') sb.append((char) ('A' + r % 26));
            else if (c >= 'a' && c <= 'z') sb.append((char) ('a' + r % 26));
            else if (c >= '0' && c <= '9') sb.append((char) ('0' + r % 10));
            else if (Character.isLetterOrDigit(c)) sb.append('x');
            else sb.append(c);
        }
        return sb.toString();
    }

    private String pick(String[] list, String real, int attempt, int slot) {
        return list[(int) Math.floorMod(Hashes.sha256Long(seed + "|" + real.toLowerCase(Locale.ROOT) + "|" + slot) + attempt, list.length)];
    }

    private long index(TermKind kind, String real, int attempt) {
        return Hashes.sha256Long(seed + "|" + kind + "|" + real.toLowerCase(Locale.ROOT)) + attempt;
    }

    /** Pseudonym of the project for the mirror folder — never the project's or the user's name. */
    public String projectPseudonym(String projectPath) {
        var a = pick(CODENAMES, "project:" + projectPath, 0, 0).toLowerCase(Locale.ROOT);
        var n = Math.floorMod(index(TermKind.PROJECT, "project:" + projectPath, 0), 900) + 100;
        return a + "-" + n;
    }

    public static List<String> builtInWords() {
        var all = new ArrayList<String>();
        for (var list : List.of(FIRST_NAMES, LAST_NAMES, COMPANY_STEMS, COMPANY_WORDS, CODENAMES, PRODUCTS)) {
            all.addAll(List.of(list));
        }
        return all;
    }
}
