package logflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ParserStageTest {

    @Test
    void parsesCommonLogFormatLine() {
        ParserStage stage = new ParserStage();
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        stage.process("192.168.0.7 - - [29/Sep/2026:10:01:18 +0300] \"PUT /cart HTTP/1.1\" 400 478", out);

        assertEquals(1, out.items().size());
        LogRecord r = out.items().get(0);
        assertEquals("192.168.0.7", r.clientIp());
        assertEquals(OffsetDateTime.of(2026, 9, 29, 10, 1, 18, 0, ZoneOffset.ofHours(3)), r.timestamp());
        assertEquals("PUT", r.method());
        assertEquals("/cart", r.path());
        assertEquals("HTTP/1.1", r.protocol());
        assertEquals(400, r.status());
        assertEquals(478, r.bytes());
        assertEquals("", r.userAgent());
        assertEquals(1, stage.parsedCount());
    }

    @Test
    void parsesCombinedFormatRefererAndUserAgent() {
        ParserStage stage = new ParserStage();
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        stage.process("10.0.0.1 - bob [29/Sep/2026:10:00:00 +0300] \"GET /api/x HTTP/1.1\" 200 - "
                + "\"https://example.com/\" \"curl/8.0\"", out);

        LogRecord r = out.items().get(0);
        assertEquals("bob", r.user());
        assertEquals(0, r.bytes());
        assertEquals("https://example.com/", r.referer());
        assertEquals("curl/8.0", r.userAgent());
    }

    @Test
    void skipsAndCountsMalformedLines() {
        ParserStage stage = new ParserStage();
        CollectingEmitter<LogRecord> out = new CollectingEmitter<>();

        stage.process("", out);
        stage.process("this is not a log line", out);
        stage.process("1.1.1.1 - - [99/Foo/2026:10:00:00 +0300] \"GET / HTTP/1.1\" 200 1", out);

        assertTrue(out.items().isEmpty());
        assertEquals(0, stage.parsedCount());
        assertEquals(3, stage.malformedCount());
    }
}
