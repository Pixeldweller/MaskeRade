package com.pixeldweller.maskerade.scan;

import com.pixeldweller.maskerade.model.Candidate;
import com.pixeldweller.maskerade.model.Term;
import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.TextCodec;
import com.pixeldweller.maskerade.util.Words;
import com.pixeldweller.maskerade.veil.PlaceholderGenerator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds terms worth veiling — pure Java, so the IntelliJ part only has to collect the files, the library
 * vocabulary and the overriding methods.
 *
 * <p>Candidates are <b>word parts</b> of identifiers and texts that are neither everyday words nor library
 * vocabulary, terms with a fixed kind (mail, host, IP, JDBC URL, user name in a path, IBAN, phone, secret) and
 * multi-word names (after {@code @author}, before {@code GmbH}, in {@code <developer>}, in name fields).
 */
public final class CandidateScanner {

    private CandidateScanner() {}

    public record Document(String path, String text, boolean test) {}

    /** What is known about the people and the project from outside the files (git, IDE, OS). */
    public record Context(Set<String> authorNames, Set<String> authorMails, String projectName, Set<String> moduleNames,
                          String osUser) {
        public static Context empty() {
            return new Context(Set.of(), Set.of(), "", Set.of(), "");
        }
    }

    public static final class Input {
        public final List<Document> documents = new ArrayList<>();
        /** Word parts of library / JDK class and package names → how many names contain them. */
        public Map<String, Integer> vocabulary = Map.of();
        /** Identifiers that override or implement a library method (from PSI); never candidates. */
        public Set<String> overrideNames = Set.of();
        public Context context = Context.empty();
        public Predicate<String> commonWord = CommonWords.builtIn();
        public Set<String> ignoredKeys = Set.of();
        public List<Term> existingTerms = List.of();
        public BooleanSupplier cancelled = () -> false;
        public DoubleConsumer progress = f -> {};
        /** Output: documents treated as minified / generated (only patterns were searched). */
        public int generatedSkipped;
    }

    private static final int MAX_LOCATIONS = 50;
    /** The result list is cut off here — sorted by score, so nothing important is lost. */
    public static final int MAX_CANDIDATES = 2000;
    public static final int MIN_SCORE = 10;

    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
    private static final Pattern GROUP_ID = Pattern.compile("<groupId>\\s*([\\w.-]+)\\s*</groupId>");
    private static final Pattern POM_NOISE = Pattern.compile("(?s)<(parent|dependencies|dependencyManagement|build|plugins|profiles)>.*?</\\1>");
    private static final Pattern OVERRIDE = Pattern.compile(
            "@Override\\s+(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|synchronized|abstract|default|native)\\s+)*"
                    + "(?:<[^>]*>\\s*)?[\\w.<>\\[\\],? ]+?\\s+(\\w+)\\s*\\(");
    private static final Pattern AUTHOR = Pattern.compile("@author\\s+(\\p{Lu}[\\p{L}'-]+(?:[ \\t]+\\p{Lu}[\\p{L}'-]+){1,3})");
    private static final Pattern DEVELOPER_NAME = Pattern.compile("<developer>[\\s\\S]*?<name>\\s*([^<]+?)\\s*</name>");
    // starts only at a word start, bounded word length: linear on long tokens
    private static final Pattern COMPANY = Pattern.compile(
            "(?<![\\p{L}\\p{N}&-])(\\p{Lu}[\\p{L}&-]{1,40}(?:[ \\t]{1,3}\\p{Lu}[\\p{L}&-]{1,40}){0,3})[ \\t]{1,3}(GmbH|AG|KG|SE|OHG|UG|Ltd\\.?|Inc\\.?|LLC|Corp\\.?|B\\.V\\.)(?!\\p{L})");
    private static final Pattern NAME_FIELD = Pattern.compile(
            "\"(?:name|fullName|customer|kunde|kundin|author|owner|contact)\"\\s*:\\s*\"(\\p{Lu}\\p{Ll}+(?:[ \\t]+\\p{Lu}\\p{Ll}+){1,2})\"");
    private static final Pattern QUOTED_NAME = Pattern.compile("\"(\\p{Lu}\\p{Ll}+[ \\t]+\\p{Lu}\\p{Ll}+)\"");
    private static final Pattern PERSON_MARKER = Pattern.compile("(?i)@author|<developer|\\bauthor\\b|\\bentwickler(in)?\\b");
    private static final Pattern COMPANY_MARKER = Pattern.compile("\\b(GmbH|AG|KG|Ltd|Inc|LLC|Corp)\\b");
    private static final Pattern CUSTOMER_MARKER = Pattern.compile("(?i)\\b(kunde|kundin|kunden|customer|customers)\\b");
    private static final Pattern IDENT = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*");
    private static final Pattern WORDISH = Pattern.compile("\\p{L}[\\p{L}\\p{N}_]*");

    // ── statistics ──────────────────────────────────────────────────────────────

    private static final class Stat {
        final String key;
        TermKind kind;
        int baseScore;
        final Map<String, Integer> forms = new HashMap<>();
        final Set<String> reasons = new LinkedHashSet<>();
        int count;
        final Set<String> files = new HashSet<>();
        final Set<String> nonTestFiles = new HashSet<>();
        final List<Candidate.Location> locations = new ArrayList<>();
        boolean identifier, properNoun, packageSegment, contract, secret;
        int packageDepth = Integer.MAX_VALUE;
        TermKind markerKind;
        final Set<String> contractReasons = new LinkedHashSet<>();

        Stat(String key) {
            this.key = key;
        }

        void hit(Document doc, String form, int line, String snippet) {
            count++;
            forms.merge(form, 1, Integer::sum);
            files.add(doc.path());
            if (!doc.test()) nonTestFiles.add(doc.path());
            if (locations.size() < MAX_LOCATIONS) locations.add(new Candidate.Location(doc.path(), line, snippet));
        }
    }

    // ── scan ────────────────────────────────────────────────────────────────────

    public static List<Candidate> scan(Input in) {
        var excluded = excludedWords(in);
        var words = new HashMap<String, Stat>();
        var fixed = new LinkedHashMap<String, Stat>();
        var names = new LinkedHashMap<String, Stat>();
        var overrideNames = new HashSet<>(in.overrideNames);

        int done = 0;
        for (var doc : in.documents) {
            if (in.cancelled.getAsBoolean()) throw new CancellationException();
            in.progress.accept((double) done++ / Math.max(1, in.documents.size()));
            var lines = new LineIndex(doc.text());
            fixedFindings(doc, lines, fixed);
            if (looksGenerated(doc.text())) {
                in.generatedSkipped++;
                continue;
            }
            overrideNames.addAll(regexOverrides(doc));
            multiWordNames(doc, lines, names, in);
            packageSegments(doc, words, excluded, in);
            tokens(doc, lines, words, excluded, overrideNames, in);
        }

        var candidates = new LinkedHashMap<String, Candidate>();
        for (var s : fixed.values()) candidates.putIfAbsent(s.key, toCandidate(s, s.baseScore + fileBonus(s, 10)));
        var nameWordKinds = new HashMap<String, TermKind>();
        for (var s : names.values()) {
            for (var w : s.key.split(" ")) nameWordKinds.putIfAbsent(w, s.kind);
            if (allWordsExcluded(s.key, excluded)) continue;
            candidates.putIfAbsent(s.key, toCandidate(s, s.baseScore + fileBonus(s, 10)));
        }
        var contextWords = contextWords(in.context);
        for (var s : words.values()) {
            var c = scoreWord(s, nameWordKinds, contextWords);
            if (c == null) continue;
            var existing = candidates.get(s.key);
            if (existing == null) candidates.put(s.key, c);
        }
        // the best ones; everything below MIN_SCORE is noise (a word seen once in a single file)
        return candidates.values().stream()
                .filter(c -> c.score() >= MIN_SCORE || c.secret())
                .sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::term))
                .limit(MAX_CANDIDATES)
                .toList();
    }

    private static int fileBonus(Stat s, int max) {
        return Math.min(max, s.files.size() * 2);
    }

    private static Candidate toCandidate(Stat s, int score) {
        var term = s.forms.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(s.key);
        var reasons = new ArrayList<>(s.reasons);
        reasons.add(s.files.size() == 1 ? "in 1 file" : "in " + s.files.size() + " files");
        boolean testOnly = s.nonTestFiles.isEmpty();
        if (testOnly) reasons.add("only in tests");
        if (s.contract) reasons.add("external contract: " + String.join(", ", s.contractReasons));
        return new Candidate(term, s.key, s.kind, score, s.count, s.files.size(), reasons, List.copyOf(s.locations),
                s.secret, s.contract, testOnly);
    }

    private static Candidate scoreWord(Stat s, Map<String, TermKind> nameWordKinds, Map<String, String> contextWords) {
        int score = 10;
        var reasons = new ArrayList<String>();
        reasons.add("not in vocabulary");
        score += Math.min(30, s.files.size() * 3);
        if (s.identifier) {
            score += 10;
            reasons.add("in identifiers");
        }
        if (s.packageSegment) {
            score += 25;
            reasons.add("package prefix");
        }
        var context = contextWords.get(s.key);
        if (context != null) {
            score += 30;
            reasons.add(context);
        }
        if (s.properNoun) {
            score += 10;
            reasons.add("capitalized in text");
        }
        if (s.markerKind != null) {
            score += 20;
            reasons.add(switch (s.markerKind) {
                case PERSON -> "next to @author";
                case COMPANY -> "next to a legal form (GmbH, Ltd …)";
                case CUSTOMER -> "next to \"customer\"";
                default -> "next to a marker";
            });
        }
        if (nameWordKinds.containsKey(s.key)) {
            score += 15;
            reasons.add("part of a name");
        }
        if (s.key.length() < 4) {
            score -= 15;
            reasons.add("short");
        }
        if (s.nonTestFiles.isEmpty()) score -= 10;

        // most specific evidence first: a name it belongs to, its place in the package, the context, a nearby marker
        TermKind kind;
        if (nameWordKinds.containsKey(s.key)) kind = nameWordKinds.get(s.key);
        else if (s.packageSegment) kind = s.packageDepth <= 1 ? TermKind.COMPANY : TermKind.PROJECT;
        else if (context != null && context.contains("author")) kind = TermKind.PERSON;
        else if (context != null && context.contains("user")) kind = TermKind.USERNAME;
        else if (context != null) kind = TermKind.PROJECT;
        else if (s.markerKind != null) kind = s.markerKind;
        else kind = TermKind.OTHER;
        s.kind = kind;
        s.reasons.addAll(reasons);
        // prefer the Capitalized spelling for the list ("Falkenrath", not "FALKENRATH")
        var capital = s.forms.entrySet().stream()
                .filter(e -> Words.caseOf(e.getKey()) == Words.Case.CAPITAL)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
        var c = toCandidate(s, score);
        return capital.map(form -> new Candidate(form, c.key(), c.kind(), c.score(), c.count(), c.files(), c.reasons(),
                c.locations(), c.secret(), c.contract(), c.testOnly())).orElse(c);
    }

    // ── sources of candidates ───────────────────────────────────────────────────

    private static void fixedFindings(Document doc, LineIndex lines, Map<String, Stat> fixed) {
        var segments = SourceSplitter.split(doc.path(), doc.text());
        var cache = new LineCache(doc.path(), lines);
        for (var f : PatternDetector.find(doc.path(), doc.text())) {
            var key = f.secret() ? f.value() : f.value().toLowerCase(Locale.ROOT);
            var s = fixed.computeIfAbsent(key, Stat::new);
            s.kind = f.kind();
            s.baseScore = Math.max(s.baseScore, f.score());
            s.secret |= f.secret();
            s.reasons.add(f.reason());
            int line = lines.lineOf(f.start());
            s.hit(doc, f.value(), line, f.secret() ? "(secret)" : TextCodec.snippet(doc.text(), f.start(), f.end()));
            cache.moveTo(line);
            var contract = SourceSplitter.inComment(segments, f.start()) ? null
                    : cache.contracts().reasonAt(f.start() - lines.startOf(line));
            if (contract != null) {
                s.contract = true;
                s.contractReasons.add(contract);
            }
        }
    }

    private static void multiWordNames(Document doc, LineIndex lines, Map<String, Stat> names, Input in) {
        var text = doc.text();
        var lowerPath = doc.path().toLowerCase(Locale.ROOT);
        boolean customerFile = lowerPath.contains("kunde") || lowerPath.contains("customer") || lowerPath.contains("client");
        name(AUTHOR, doc, lines, names, TermKind.PERSON, 75, "@author", in, false);
        name(DEVELOPER_NAME, doc, lines, names, TermKind.PERSON, 75, "developer in the build file", in, false);
        name(COMPANY, doc, lines, names, TermKind.COMPANY, 75, "company name (legal form)", in, true);
        name(NAME_FIELD, doc, lines, names, customerFile ? TermKind.CUSTOMER : TermKind.PERSON, 65,
                customerFile ? "customer name" : "name field", in, false);
        if (SourceSplitter.isCode(doc.path())) {
            var m = QUOTED_NAME.matcher(text);
            while (m.find()) {
                var parts = m.group(1).split("[ \\t]+");
                if (Arrays.stream(parts).allMatch(p -> isUnknown(p, in))) {
                    var lineText = lines.lineText(lines.lineOf(m.start()));
                    var kind = CUSTOMER_MARKER.matcher(lineText).find() ? TermKind.CUSTOMER : TermKind.PERSON;
                    addName(doc, lines, names, m.group(1), m.start(1), m.end(1), kind, 55, "name in a string literal");
                }
            }
        }
    }

    private static void name(Pattern p, Document doc, LineIndex lines, Map<String, Stat> names, TermKind kind, int score,
                             String reason, Input in, boolean stripCommonPrefix) {
        Matcher m = p.matcher(doc.text());
        while (m.find()) {
            var value = m.group(1).trim();
            int start = m.start(1);
            if (stripCommonPrefix) {
                // "Entry point of Falkenrath Logistik GmbH": drop leading everyday words
                var parts = new ArrayList<>(List.of(value.split("[ \\t]+")));
                while (parts.size() > 1 && in.commonWord.test(parts.getFirst())) {
                    start += parts.getFirst().length() + 1;
                    parts.removeFirst();
                }
                value = String.join(" ", parts);
                if (parts.stream().allMatch(w -> in.commonWord.test(w))) continue;
            }
            addName(doc, lines, names, value, start, start + value.length(), kind, score, reason);
        }
    }

    private static void addName(Document doc, LineIndex lines, Map<String, Stat> names, String value, int start, int end,
                                TermKind kind, int score, String reason) {
        var key = Words.key(value);
        if (key.isBlank()) return;
        var s = names.computeIfAbsent(key, Stat::new);
        if (s.kind == null || score > s.baseScore) s.kind = kind;
        s.baseScore = Math.max(s.baseScore, score);
        s.reasons.add(reason);
        s.hit(doc, value, lines.lineOf(start), TextCodec.snippet(doc.text(), start, end));
    }

    private static void packageSegments(Document doc, Map<String, Stat> words, Set<String> excluded, Input in) {
        var segments = new ArrayList<String[]>();
        var m = PACKAGE.matcher(doc.text());
        if (SourceSplitter.isCode(doc.path()) && m.find()) segments.add(m.group(1).split("\\."));
        if (doc.path().endsWith("pom.xml")) {
            var g = GROUP_ID.matcher(POM_NOISE.matcher(doc.text()).replaceAll(""));
            if (g.find()) segments.add(g.group(1).split("\\."));
        }
        for (var parts : segments) {
            for (int i = 1; i < parts.length; i++) {
                for (var w : Words.letterParts(parts[i])) {
                    var key = w.toLowerCase(Locale.ROOT);
                    if (excluded(key, excluded, in)) continue;
                    var s = words.computeIfAbsent(key, Stat::new);
                    s.packageSegment = true;
                    s.packageDepth = Math.min(s.packageDepth, i);
                    if (doc.path().endsWith("pom.xml")) {
                        s.contract = true;
                        s.contractReasons.add("Maven/Gradle coordinates");
                    }
                }
            }
        }
    }

    private static void tokens(Document doc, LineIndex lines, Map<String, Stat> words, Set<String> excluded,
                               Set<String> overrideNames, Input in) {
        var text = doc.text();
        var cache = new LineCache(doc.path(), lines);
        for (var seg : SourceSplitter.split(doc.path(), text)) {
            var m = (seg.code() ? IDENT : WORDISH).matcher(text).region(seg.start(), seg.end());
            while (m.find()) {
                var token = m.group();
                if (seg.code() && overrideNames.contains(token)) continue;
                int tokenStart = m.start();
                int offset = 0;
                for (var part : Words.split(token)) {
                    int partStart = token.indexOf(part, offset);
                    offset = partStart + part.length();
                    if (!Character.isLetter(part.charAt(0))) continue;
                    var key = part.toLowerCase(Locale.ROOT);
                    if (key.length() < 3 || excluded(key, excluded, in)) continue;
                    var s = words.computeIfAbsent(key, Stat::new);
                    int abs = tokenStart + partStart;
                    int line = lines.lineOf(abs);
                    cache.moveTo(line);
                    s.hit(doc, part, line, s.locations.size() < MAX_LOCATIONS
                            ? TextCodec.snippet(text, tokenStart, tokenStart + token.length()) : null);
                    if (seg.code()) {
                        s.identifier = true;
                    } else {
                        if (Words.caseOf(part) == Words.Case.CAPITAL && partStart == 0 && !sentenceStart(text, tokenStart)) {
                            s.properNoun = true;
                        }
                        var marker = cache.marker();
                        if (marker != null && (s.markerKind == null || marker == TermKind.PERSON)) s.markerKind = marker;
                    }
                    var contract = seg.comment() ? null : cache.contracts().reasonAt(abs - lines.startOf(line));
                    if (contract != null) {
                        s.contract = true;
                        s.contractReasons.add(contract);
                    }
                }
            }
        }
    }

    /**
     * Per-line results, computed once per line instead of once per word — a minified file is one huge line,
     * and re-reading it for every word made the scan quadratic.
     */
    private static final class LineCache {
        private final String path;
        private final LineIndex lines;
        private int line = -1;
        private String text;
        private TermKind marker;
        private boolean markerDone;
        private Contracts.Line contracts;

        LineCache(String path, LineIndex lines) {
            this.path = path;
            this.lines = lines;
        }

        void moveTo(int line) {
            if (line == this.line) return;
            this.line = line;
            text = lines.lineText(line);
            markerDone = false;
            contracts = null;
        }

        TermKind marker() {
            if (!markerDone) {
                marker = markerKind(text);
                markerDone = true;
            }
            return marker;
        }

        Contracts.Line contracts() {
            if (contracts == null) contracts = Contracts.line(path, text);
            return contracts;
        }
    }

    /** Minified or generated: some line is longer than this. Only patterns (hosts, mails, secrets) are searched. */
    private static final int GENERATED_LINE_LENGTH = 5000;

    static boolean looksGenerated(String text) {
        int lineStart = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                if (i - lineStart > GENERATED_LINE_LENGTH) return true;
                lineStart = i + 1;
            }
        }
        return text.length() - lineStart > GENERATED_LINE_LENGTH;
    }

    private static TermKind markerKind(String line) {
        if (PERSON_MARKER.matcher(line).find()) return TermKind.PERSON;
        if (COMPANY_MARKER.matcher(line).find()) return TermKind.COMPANY;
        if (CUSTOMER_MARKER.matcher(line).find()) return TermKind.CUSTOMER;
        return null;
    }

    /** At the start of a sentence a capital letter says nothing. */
    private static boolean sentenceStart(String text, int index) {
        int i = index - 1;
        while (i >= 0 && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) i--;
        if (i < 0) return true;
        char c = text.charAt(i);
        if (c == '\n' || c == '\r' || c == '.' || c == '!' || c == '?' || c == '#' || c == '>') return true;
        // "* Text" in Javadoc, "- item" in Markdown
        return (c == '*' || c == '-') && (i == 0 || Character.isWhitespace(text.charAt(i - 1)));
    }

    private static Set<String> regexOverrides(Document doc) {
        if (!SourceSplitter.isCode(doc.path())) return Set.of();
        var out = new HashSet<String>();
        var m = OVERRIDE.matcher(doc.text());
        while (m.find()) out.add(m.group(1));
        return out;
    }

    // ── exclusion ───────────────────────────────────────────────────────────────

    private static Set<String> excludedWords(Input in) {
        var out = new HashSet<String>(in.ignoredKeys);
        for (var w : PlaceholderGenerator.builtInWords()) out.add(w.toLowerCase(Locale.ROOT));
        for (var t : in.existingTerms) {
            out.add(t.key());
            if (!t.isLiteral()) {
                for (var w : t.realWords()) {
                    var lower = w.toLowerCase(Locale.ROOT);
                    if (lower.length() >= 4 && !in.commonWord.test(lower)) out.add(lower);
                }
            }
        }
        return out;
    }

    private static boolean excluded(String key, Set<String> excluded, Input in) {
        return excluded.contains(key) || in.commonWord.test(key) || in.vocabulary.getOrDefault(key, 0) >= 2;
    }

    private static boolean isUnknown(String word, Input in) {
        var key = word.toLowerCase(Locale.ROOT);
        return !in.commonWord.test(key) && in.vocabulary.getOrDefault(key, 0) < 2;
    }

    private static boolean allWordsExcluded(String key, Set<String> excluded) {
        return excluded.contains(key);
    }

    private static Map<String, String> contextWords(Context c) {
        var out = new HashMap<String, String>();
        for (var name : c.authorNames()) {
            for (var w : Words.letterParts(name)) out.putIfAbsent(w.toLowerCase(Locale.ROOT), "matches a git author");
        }
        for (var mail : c.authorMails()) {
            for (var w : Words.letterParts(mail)) out.putIfAbsent(w.toLowerCase(Locale.ROOT), "matches a git author's mail");
        }
        for (var w : Words.letterParts(c.projectName())) out.putIfAbsent(w.toLowerCase(Locale.ROOT), "matches the project name");
        for (var module : c.moduleNames()) {
            for (var w : Words.letterParts(module)) out.putIfAbsent(w.toLowerCase(Locale.ROOT), "matches a module name");
        }
        if (c.osUser() != null && !c.osUser().isBlank()) out.putIfAbsent(c.osUser().toLowerCase(Locale.ROOT), "matches the OS user name");
        return out;
    }

    // ── line index ──────────────────────────────────────────────────────────────

    private static final class LineIndex {
        private final String text;
        private final int[] starts;

        LineIndex(String text) {
            this.text = text;
            var list = new ArrayList<Integer>();
            list.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') list.add(i + 1);
            }
            starts = list.stream().mapToInt(Integer::intValue).toArray();
        }

        /** 1-based */
        int lineOf(int index) {
            int pos = Arrays.binarySearch(starts, index);
            return (pos >= 0 ? pos : -pos - 2) + 1;
        }

        int startOf(int line) {
            return starts[line - 1];
        }

        String lineText(int line) {
            int start = starts[line - 1];
            int end = line < starts.length ? starts[line] - 1 : text.length();
            return text.substring(start, Math.max(start, end));
        }
    }
}
