package ai.opsmind.platform.common.api;

import java.text.Normalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Deterministic last-hop redaction for operator-visible, non-model-authored text.
 * Model-authored prose is withheld by the operator projection assembler instead.
 */
public final class OperatorDisplayRedactor {

    private static final List<Rule> RULES = List.of(
        new Rule(Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?"
                + "-----END [A-Z0-9 ]*PRIVATE KEY-----"
        ), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile(
            "(?im)(?:[\"']\\s*)?\\b(?:authorization|proxy[-_]?authorization|"
                + "x[-_]?api[-_]?key|api[-_]?key|api[-_]?credential|password|passwd|"
                + "access[-_]?token|refresh[-_]?token|bearer[-_]?token|"
                + "authorization[-_]?header|token|client[-_]?secret|cookie|set[-_]?cookie)\\b"
                + "(?:\\s*[\"'])?\\s*[:=]\\s*"
                + "(?:(?:bearer|basic|token)\\s+)?"
                + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;}]+)"
        ), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile("(?i)\\b(?:sk|ds)-[a-z0-9_-]{16,}\\b"), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\b"
        ), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"), "[REDACTED_SECRET]"),
        new Rule(Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE
        ), "[REDACTED_EMAIL]"),
        new Rule(Pattern.compile("(?i)\\bhttps?://[^\\s<>\"']+"), "[REDACTED_URL]"),
        new Rule(Pattern.compile(
            "(?im)\\b(?:system[-_ ]?prompt|raw[-_ ]?prompt|reasoning(?:[-_ ]?content)?|"
                + "chain[-_ ]?of[-_ ]?thought|promql|query|sql)\\b\\s*[:=]\\s*"
                + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;}]+)"
        ), "[REDACTED_EXECUTABLE_TEXT]"),
        new Rule(Pattern.compile(
            "(?i)\\b(?:rate|irate|increase|histogram_quantile|sum|avg|min|max|count)"
                + "\\s*\\([^()\\r\\n]{1,512}\\)"
        ), "[REDACTED_QUERY]"),
        new Rule(Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|\\p{Cf}"), "")
    );

    public Redaction redact(String value, String nullFallback) {
        if (nullFallback == null || nullFallback.isBlank()) {
            throw new IllegalArgumentException("Display redaction fallback is required.");
        }
        if (value == null) {
            return new Redaction(nullFallback, 0);
        }
        String current = Normalizer.normalize(value, Normalizer.Form.NFKC);
        for (Rule rule : RULES) {
            current = rule.pattern().matcher(current).replaceAll(rule.replacement());
        }
        String result = current.trim();
        return new Redaction(result, result.equals(value) ? 0 : 1);
    }

    public record Redaction(String value, int count) {
        public Redaction {
            if (value == null || count < 0) {
                throw new IllegalArgumentException("Display redaction result is invalid.");
            }
        }
    }

    private record Rule(Pattern pattern, String replacement) { }
}
