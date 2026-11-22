package com.pixeldweller.maskerade.scan;

import com.pixeldweller.maskerade.model.TermKind;
import com.pixeldweller.maskerade.util.TextCodec;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terms with a fixed kind, found by their shape: mail addresses, hosts, IPs, JDBC URLs, user names in paths, IBANs,
 * phone numbers — and secrets, which are reported ("does not belong into the repository") and veiled with a dummy.
 */
public final class PatternDetector {

    private PatternDetector() {}

    public record Finding(TermKind kind, String value, int score, String reason, int start, int end, boolean secret) {}

    private static final Pattern MAIL = Pattern.compile(
            "(?<![\\w.%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}(?![\\w-])");
    private static final Pattern JDBC = Pattern.compile("jdbc:[a-z0-9]+:[^\\s\"'<>`]+", Pattern.CASE_INSENSITIVE);
    private static final String INTERNAL_TLDS = "lan|local|intern|internal|corp|home|localdomain|private|priv";
    private static final Pattern HOST = Pattern.compile(
            "(?<![\\w.@-])(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+(?:" + INTERNAL_TLDS
                    + "|de|com|net|org|io|eu|at|ch|uk|info|biz|cloud|dev|app|nl|fr|it|es|pl|se|dk|no)(?![\\w-])(?!\\.[a-zA-Z])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IP = Pattern.compile(
            "(?<![\\d.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\d]|\\.\\d)");
    private static final Pattern WINDOWS_USER = Pattern.compile(
            "(?i)\\b[a-z]:(?:\\\\\\\\|\\\\|/)users(?:\\\\\\\\|\\\\|/)([^\\\\/:*?\"<>|\\s]+)");
    private static final Pattern UNIX_HOME = Pattern.compile("(?<![\\w.])/(?:home|Users)/([a-z_][a-z0-9_.-]{1,31})(?=[/\\s\"']|$)");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b");
    private static final Pattern PHONE = Pattern.compile("(?<![\\w+./-])(?:\\+\\d{1,3}|0)\\d{1,4}[ /-]\\d{3,}(?:[ -]\\d{2,})*(?![\\w./-])");

    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgh[poustr]_[A-Za-z0-9]{30,}"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{30,}"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bxox[abposr]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"));
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)([\\w.-]*(?:password|passwd|pwd|secret|token|api[_.-]?key|apikey|access[_.-]?key|private[_.-]?key|credential|key))"
                    + "[\"']?\\s*[:=]\\s*([\"']?)([^\\s\"'<>;,()]{8,})\\2");

    private static final Set<String> PUBLIC_HOSTS = Set.of(
            "apache.org", "w3.org", "spring.io", "github.com", "gitlab.com", "jetbrains.com", "google.com", "oracle.com",
            "openjdk.org", "java.net", "maven.org", "junit.org", "javadoc.io", "mockito.org", "gradle.org", "npmjs.com",
            "npmjs.org", "microsoft.com", "eclipse.org", "jakarta.ee", "json-schema.org", "schema.org", "mozilla.org",
            "stackoverflow.com", "wikipedia.org", "youtube.com", "docker.com", "docker.io", "kotlinlang.org", "jcenter.bintray.com",
            "sonatype.org", "springframework.org", "hibernate.org", "jboss.org", "fasterxml.com", "slf4j.org", "logback.qos.ch",
            "anthropic.com", "openai.com", "localhost.localdomain");
    private static final Set<String> DEFAULT_USERS = Set.of("public", "default", "all users", "default user", "defaultuser0",
            "administrator", "user", "username", "%username%", "runner", "root", "${user.name}", "$user", "<user>", "you", "me");

    public static List<Finding> find(String path, String text) {
        var out = new ArrayList<Finding>();
        boolean code = SourceSplitter.isCode(path);
        var segments = code ? SourceSplitter.split(path, text) : List.<SourceSplitter.Segment>of();

        each(MAIL, text, m -> {
            var mail = m.group();
            var local = mail.substring(0, mail.indexOf('@')).toLowerCase(Locale.ROOT);
            var domain = mail.substring(mail.indexOf('@') + 1).toLowerCase(Locale.ROOT);
            if (local.equals("git") || isExampleHost(domain)) return;
            out.add(new Finding(TermKind.MAIL, mail, 60, "mail address", m.start(), m.end(), false));
        });
        each(JDBC, text, m -> out.add(new Finding(TermKind.HOST, trimUrl(m.group()), 60, "database URL", m.start(), m.end(), false)));
        each(HOST, text, m -> {
            var host = m.group().toLowerCase(Locale.ROOT);
            if (isExampleHost(host) || isPublicHost(host)) return;
            // "user@host" is reported as mail, "java.io" in code is a package, "README.de" a file name
            if (m.start() > 0 && text.charAt(m.start() - 1) == '@' && !isPrecededByGit(text, m.start())) return;
            if (code && !insideQuotesOrComment(segments, m.start())) return;
            boolean internal = host.matches(".*\\.(" + INTERNAL_TLDS + ")$");
            out.add(new Finding(TermKind.HOST, host, internal ? 65 : 40, internal ? "internal host" : "domain",
                    m.start(), m.end(), false));
        });
        each(IP, text, m -> {
            var ip = m.group();
            if (ip.startsWith("127.") || ip.startsWith("0.") || ip.startsWith("255.") || ip.startsWith("192.0.2.")
                    || ip.startsWith("198.51.100.") || ip.startsWith("203.0.113.")) return;
            if (looksLikeVersion(text, m.start())) return;
            boolean priv = isPrivate(ip);
            out.add(new Finding(TermKind.HOST, ip, priv ? 55 : 30, priv ? "private IP address" : "IP address", m.start(), m.end(), false));
        });
        each(WINDOWS_USER, text, m -> addUser(out, m));
        each(UNIX_HOME, text, m -> addUser(out, m));
        each(IBAN, text, m -> {
            if (validIban(m.group())) out.add(new Finding(TermKind.OTHER, m.group(), 50, "IBAN", m.start(), m.end(), false));
        });
        each(PHONE, text, m -> {
            var digits = m.group().replaceAll("\\D", "");
            if (digits.length() >= 8 && digits.length() <= 15 && !code) {
                out.add(new Finding(TermKind.OTHER, m.group(), 35, "phone number", m.start(), m.end(), false));
            }
        });

        for (var p : SECRET_PATTERNS) {
            each(p, text, m -> out.add(new Finding(TermKind.SECRET, m.group(), 100, "secret (known key format)", m.start(), m.end(), true)));
        }
        each(SECRET_ASSIGNMENT, text, m -> {
            var value = m.group(3);
            boolean quoted = !m.group(2).isEmpty();
            if (code && !quoted) return; // "token = service.create()" is code, not a key
            if (value.startsWith("${") || value.startsWith("$") || value.startsWith("%") || value.startsWith("{{")) return;
            if (!looksSecret(value)) return;
            int start = m.start(3);
            if (out.stream().anyMatch(f -> f.secret && f.start <= start && f.end >= start)) return;
            out.add(new Finding(TermKind.SECRET, value, 100, "secret assigned to \"" + m.group(1) + "\"", start, m.end(3), true));
        });
        return out;
    }

    private static void addUser(List<Finding> out, Matcher m) {
        var user = m.group(1);
        if (DEFAULT_USERS.contains(user.toLowerCase(Locale.ROOT)) || user.startsWith("$") || user.startsWith("%")) return;
        out.add(new Finding(TermKind.USERNAME, user, 70, "user name in a path", m.start(1), m.end(1), false));
    }

    private interface MatchConsumer {
        void accept(Matcher m);
    }

    private static void each(Pattern p, String text, MatchConsumer c) {
        var m = p.matcher(text);
        while (m.find()) c.accept(m);
    }

    private static String trimUrl(String url) {
        return url.replaceAll("[.,;)\\]}]+$", "");
    }

    static boolean isExampleHost(String host) {
        return host.equals("example.org") || host.equals("example.com") || host.equals("example.net")
                || host.endsWith(".example") || host.contains("example.") || host.endsWith(".invalid")
                || host.endsWith(".test") || host.endsWith(".localhost") || host.equals("localhost");
    }

    private static boolean isPublicHost(String host) {
        for (var p : PUBLIC_HOSTS) {
            if (host.equals(p) || host.endsWith("." + p)) return true;
        }
        return false;
    }

    private static boolean isPrecededByGit(String text, int at) {
        return at >= 4 && text.startsWith("git@", at - 4);
    }

    /** In code, hosts only count in strings and comments ("java.io" or "foo.de" as an expression are not hosts). */
    private static boolean insideQuotesOrComment(List<SourceSplitter.Segment> segments, int index) {
        for (var s : segments) {
            if (index >= s.start() && index < s.end()) return !s.code();
        }
        return false;
    }

    private static boolean looksLikeVersion(String text, int start) {
        var before = text.substring(Math.max(0, start - 12), start).toLowerCase(Locale.ROOT);
        return before.matches(".*(version|v|ver|release)[\\s:=\"'>]*$");
    }

    static boolean isPrivate(String ip) {
        var p = ip.split("\\.");
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
    }

    private static boolean validIban(String raw) {
        var iban = raw.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) return false;
        var rearranged = iban.substring(4) + iban.substring(0, 4);
        var digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else if (c >= 'A' && c <= 'Z') digits.append(c - 'A' + 10);
            else return false;
        }
        return new BigInteger(digits.toString()).mod(BigInteger.valueOf(97)).intValue() == 1;
    }

    /** Long enough and either high entropy or a mix of character classes. */
    static boolean looksSecret(String value) {
        if (value.length() < 8) return false;
        var lower = value.toLowerCase(Locale.ROOT);
        if (Set.of("changeme", "password", "secret12", "xxxxxxxx", "placeholder", "undefined").contains(lower)) return false;
        if (lower.matches("[a-z]+([._-][a-z]+)*")) return false; // a word or property name, e.g. "my.secret.name"
        int classes = 0;
        if (value.chars().anyMatch(Character::isUpperCase)) classes++;
        if (value.chars().anyMatch(Character::isLowerCase)) classes++;
        if (value.chars().anyMatch(Character::isDigit)) classes++;
        if (value.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch))) classes++;
        return entropy(value) >= 3.5 || (value.length() >= 16 && classes >= 3) || (value.length() >= 24 && classes >= 2);
    }

    static double entropy(String s) {
        var counts = new int[128];
        int other = 0;
        for (char c : s.toCharArray()) {
            if (c < 128) counts[c]++;
            else other++;
        }
        double e = 0;
        for (int count : counts) {
            if (count == 0) continue;
            double p = (double) count / s.length();
            e -= p * (Math.log(p) / Math.log(2));
        }
        if (other > 0) {
            double p = (double) other / s.length();
            e -= p * (Math.log(p) / Math.log(2));
        }
        return e;
    }

    /** Line of a finding, for locations. */
    public static int lineOf(String text, Finding f) {
        return TextCodec.lineOf(text, f.start);
    }
}
