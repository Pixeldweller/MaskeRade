package com.pixeldweller.maskerade.scan;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Places where a project talks to the outside world. Renaming there is consistent inside the mirror, but the
 * mirror then behaves differently from the original ("external contract") — reported, not prevented.
 * Documentation files and comments never count: they do not change behaviour.
 */
public final class Contracts {

    private Contracts() {}

    private record Rule(Pattern pattern, String reason) {}

    private static final List<Rule> LINE_RULES = List.of(
            new Rule(Pattern.compile("@(JsonProperty|JsonAlias|JsonTypeName|SerializedName|XmlElement|XmlAttribute)\\b"), "JSON/XML field name"),
            new Rule(Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\b|@Path\\(|@FeignClient"), "REST path"),
            new Rule(Pattern.compile("@(Table|Column|JoinColumn|JoinTable|Entity|SecondaryTable)\\b"), "database table/column"),
            new Rule(Pattern.compile("\\b(SELECT|INSERT|UPDATE|DELETE|FROM|INTO|JOIN|TABLE|WHERE)\\b"), "database table/column"),
            new Rule(Pattern.compile("@Value\\(\\s*\"\\$\\{|@ConfigurationProperties|getProperty\\(|getenv\\("), "property key set from outside"),
            new Rule(Pattern.compile("@(Component|Service|Repository|Controller|RestController|Named|Qualifier)\\(\\s*\"|@Bean\\(\\s*(name\\s*=\\s*)?\""), "bean name"),
            new Rule(Pattern.compile("<(groupId|artifactId)>|^\\s*group\\s*=|implementation\\s*\\(?\\s*[\"']"), "Maven/Gradle coordinates"),
            new Rule(Pattern.compile("getMessage\\(\\s*\"|@Message\\b|messageSource"), "message key"));

    private static final String URL_REASON = "URL of a real server";
    private static final Pattern URL = Pattern.compile(
            "(?i)\\b(?:https?|ftp|ssh|wss?|jdbc:[a-z0-9]+)://[^\\s\"'<>)\\]`]+|\\bgit@[\\w.-]+:[^\\s\"'<>`]*");
    private static final Pattern LOCAL_URL = Pattern.compile(
            "(?i)^[a-z0-9:]+//(?:[^/@]*@)?(localhost|127\\.\\d+\\.\\d+\\.\\d+|0\\.0\\.0\\.0|\\[::1]|[^/:]*\\.example|[^/:]*example\\.(?:org|com|net)|[^/:]*\\.test|[^/:]*\\.invalid)(?:[:/]|$).*");
    private static final List<String> DOC_EXTENSIONS = List.of(".md", ".markdown", ".txt", ".adoc", ".asciidoc", ".rst", ".html", ".htm");

    /** Why a replacement on this line may change the behaviour, or null. */
    public static String reasonFor(String path, String line) {
        return reasonFor(path, line, -1);
    }

    /**
     * @param column where on the line the replacement starts, -1 if unknown — in .properties/.yml files the key
     *               part of {@code key = value} is a contract (set from outside); URLs only count if the
     *               replacement lies inside one
     */
    public static String reasonFor(String path, String line, int column) {
        return line(path, line).reasonAt(column);
    }

    /** Evaluates a line once; {@link Line#reasonAt} is then cheap for every replacement on it (long lines!). */
    public static Line line(String path, String line) {
        var name = path.toLowerCase(Locale.ROOT).substring(path.lastIndexOf('/') + 1);
        if (DOC_EXTENSIONS.stream().anyMatch(name::endsWith) || name.equals("license") || name.equals("notice")) return Line.NONE;
        boolean config = name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".env");
        if (config) {
            if (line.stripLeading().startsWith("#")) return Line.NONE;
            int sep = indexOfSeparator(line);
            if (sep > 0) {
                var keyReason = name.startsWith("messages") ? "message key" : "property key set from outside";
                return new Line(keyReason, sep, Rules.of(line.substring(sep + 1), sep + 1));
            }
        }
        return new Line(null, -1, Rules.of(line, 0));
    }

    /** Contract information of one line. */
    public static final class Line {
        static final Line NONE = new Line(null, -1, new Rules(List.of(), null));

        private final String keyReason;
        private final int separator;
        private final Rules rules;

        private Line(String keyReason, int separator, Rules rules) {
            this.keyReason = keyReason;
            this.separator = separator;
            this.rules = rules;
        }

        /** @param column -1 = anywhere on the line */
        public String reasonAt(int column) {
            if (keyReason != null && (column < 0 || column < separator)) return keyReason;
            return rules.reasonAt(column);
        }
    }

    /** URL spans (absolute columns) and the first line rule that matches. */
    private record Rules(List<int[]> urlSpans, String ruleReason) {
        static Rules of(String text, int offset) {
            var spans = new java.util.ArrayList<int[]>();
            var url = URL.matcher(text);
            while (url.find()) {
                if (!LOCAL_URL.matcher(url.group()).matches()) spans.add(new int[]{url.start() + offset, url.end() + offset});
            }
            String reason = null;
            for (var r : LINE_RULES) {
                if (r.pattern.matcher(text).find()) {
                    reason = r.reason;
                    break;
                }
            }
            return new Rules(spans, reason);
        }

        String reasonAt(int column) {
            for (var s : urlSpans) {
                if (column < 0 || (column >= s[0] && column < s[1])) return URL_REASON;
            }
            return ruleReason;
        }
    }

    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) return colon;
        if (colon < 0) return eq;
        return Math.min(eq, colon);
    }
}
