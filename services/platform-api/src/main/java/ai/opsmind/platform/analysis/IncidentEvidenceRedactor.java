package ai.opsmind.platform.analysis;

import java.util.List;
import java.util.regex.Pattern;

/** Deterministic last-hop redaction for authoritative incident snapshot fields. */
final class IncidentEvidenceRedactor {

    private static final String SECRET = "[REDACTED_SECRET]";
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
        Pattern.compile(
            "\\beyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\b"
        ),
        Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
        Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"),
        Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
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

    String redact(String value) {
        if (value == null) {
            return "not recorded";
        }
        String redacted = value;
        for (Pattern pattern : SECRET_PATTERNS) {
            redacted = pattern.matcher(redacted).replaceAll(SECRET);
        }
        redacted = EMAIL.matcher(redacted).replaceAll("[REDACTED_EMAIL]");
        return CONTROL.matcher(redacted).replaceAll("").trim();
    }
}
