package logflow;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Common Log Format (isteğe bağlı Combined alanlarıyla) satırını {@link LogRecord}'a çevirir.
 * Bozuk satırlar (boş satırlar dahil) atlanır ve sayılır.
 */
public class ParserStage implements Stage<String, LogRecord>, Reportable {
    private static final Pattern CLF = Pattern.compile(
            "^(\\S+) \\S+ (\\S+) \\[([^\\]]+)\\] \"(\\S+) (\\S+)(?: (\\S+))?\" (\\d{3}) (\\d+|-)"
                    + "(?: \"([^\"]*)\" \"([^\"]*)\")?\\s*$");
    static final DateTimeFormatter CLF_TIME =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    private long parsed;
    private long malformed;

    @Override
    public void process(String line, Emitter<LogRecord> out) {
        LogRecord record = parse(line);
        if (record == null) {
            malformed++;
            return;
        }
        parsed++;
        out.emit(record);
    }

    /** Satırı ayrıştırır; biçim geçersizse {@code null} döner. */
    static LogRecord parse(String line) {
        if (line == null) {
            return null;
        }
        Matcher m = CLF.matcher(line);
        if (!m.matches()) {
            return null;
        }
        try {
            OffsetDateTime ts = OffsetDateTime.parse(m.group(3), CLF_TIME);
            String user = "-".equals(m.group(2)) ? "" : m.group(2);
            long bytes = "-".equals(m.group(8)) ? 0 : Long.parseLong(m.group(8));
            String referer = m.group(9) == null || "-".equals(m.group(9)) ? "" : m.group(9);
            return new LogRecord(m.group(1), user, ts, m.group(4), m.group(5), m.group(6),
                    Integer.parseInt(m.group(7)), bytes, referer, m.group(10), null);
        } catch (DateTimeParseException | NumberFormatException e) {
            return null;
        }
    }

    public long parsedCount() {
        return parsed;
    }

    public long malformedCount() {
        return malformed;
    }

    @Override
    public String report() {
        return "parse      parsed: " + parsed + ", malformed (skipped): " + malformed;
    }
}
