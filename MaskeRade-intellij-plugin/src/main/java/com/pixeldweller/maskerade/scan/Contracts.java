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
        var name = path.toLowerCase(Locale.ROOT).substring(path.lastIndexOf('/') + 1);
        if (DOC_EXTENSIONS.stream().anyMatch(name::endsWith) || name.equals("license") || name.equals("notice")) return null;
        boolean config = name.endsWith(".properties") || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".env");
        if (config) {
            if (line.stripLeading().startsWith("#")) return null;
            int sep = indexOfSeparator(line);
            if (sep > 0) {
                boolean inKey = column < 0 || column < sep;
                if (inKey) return name.startsWith("messages") ? "message key" : "property key set from outside";
                return matchRules(line.substring(sep + 1), column - sep - 1);
            }
        }
        return matchRules(line, column);
    }

    private static String matchRules(String line, int column) {
        var url = URL.matcher(line);
        while (url.find()) {
            boolean inside = column < 0 || (column >= url.start() && column < url.end());
            if (inside && !LOCAL_URL.matcher(url.group()).matches()) return URL_REASON;
        }
        for (var r : LINE_RULES) {
            if (r.pattern.matcher(line).find()) return r.reason;
        }
        return null;
    }

    private static int indexOfSeparator(String line) {
        int eq = line.indexOf('=');
        int colon = line.indexOf(':');
        if (eq < 0) return colon;
        if (colon < 0) return eq;
        return Math.min(eq, colon);
    }
}
