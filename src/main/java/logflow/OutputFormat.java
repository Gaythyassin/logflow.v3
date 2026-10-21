package logflow;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Sink'lerin bir kaydı satıra çevirme biçimleri. */
public enum OutputFormat {
    /** {@code LogRecord} için v2 tek satır biçimi, {@code ProjectedRecord} için {@code key=value}. */
    TEXT,
    /** Başlık satırı ilk kayıttan alınan virgülle ayrılmış değerler (RFC 4180 tırnaklama). */
    CSV;

    /** Yeni bir biçimlendirici döndürür; CSV başlığı izlediği için her sink kendi örneğini almalıdır. */
    public Function<Object, String> formatter() {
        return switch (this) {
            case TEXT -> item -> item instanceof LogRecord r ? ConsoleSink.format(r) : String.valueOf(item);
            case CSV -> new Function<>() {
                private boolean headerWritten;

                @Override
                public String apply(Object item) {
                    Map<String, String> fields = fieldsOf(item);
                    String row = csvRow(fields.values().toArray(String[]::new));
                    if (headerWritten) {
                        return row;
                    }
                    headerWritten = true;
                    return csvRow(fields.keySet().toArray(String[]::new)) + "\n" + row;
                }
            };
        };
    }

    static Map<String, String> fieldsOf(Object item) {
        if (item instanceof ProjectedRecord p) {
            return p.fields();
        }
        if (item instanceof LogRecord r) {
            Map<String, String> all = new LinkedHashMap<>();
            LogRecord.FIELD_NAMES.forEach(f -> all.put(f, r.field(f)));
            return all;
        }
        return Map.of("value", String.valueOf(item));
    }

    static String csvRow(String... values) {
        return Arrays.stream(values).map(OutputFormat::csvCell).collect(Collectors.joining(","));
    }

    private static String csvCell(String v) {
        boolean quote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        return quote ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }
}
