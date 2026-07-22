package ai.opsmind.toolgateway.application;

import java.util.List;
import java.util.regex.Pattern;

/** Deterministic last-hop redaction for untrusted connector text values. */
final class SensitiveValueRedactor {

    private static final String REDACTED = "[REDACTED]";
    private static final List<Pattern> SECRET_PATTERNS = List.of(
        Pattern.compile("(?i)\\b(?:sk|ds)-[a-z0-9_-]{16,}\\b"),
        Pattern.compile(
            "(?im)(?:[\"']\\s*)?\\b(?:authorization|proxy[-_]?authorization|"
                + "x[-_]?api[-_]?key|api[-_]?key|api[-_]?credential|password|passwd|"
                + "access[-_]?token|refresh[-_]?token|bearer[-_]?token|"
                + "authorization[-_]?header|token|client[-_]?secret|cookie|set[-_]?cookie)\\b"
                + "(?:\\s*[\"'])?\\s*[:=]\\s*"
                + "(?:(?:bearer|basic|token)\\s+)?"
                + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\r\\n,;}]+)"
        ),
        Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9._~+/-]{8,}=*"),
        Pattern.compile(
            "(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}"
                + "\\.[A-Za-z0-9_-]{8,}(?![A-Za-z0-9_-])"
        ),
        Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
        Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
        Pattern.compile("(?i)https?://[^/@:\\s]+:[^/@\\s]+@[^\\s]+"),
        Pattern.compile(
            "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?"
                + "-----END [A-Z0-9 ]*PRIVATE KEY-----"
        )
    );
    private static final Pattern EMAIL = Pattern.compile(
        "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
        Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private SensitiveValueRedactor() { }

    static String redact(String value) {
        String redacted = value;
        for (Pattern pattern : SECRET_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(REDACTED);
        }
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
        return CONTROL.matcher(redacted).replaceAll("");
    }
}
