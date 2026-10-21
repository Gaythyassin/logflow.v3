package logflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Akıcı, tip güvenli pipeline birleştirici.
 *
 * <pre>{@code
 * Pipeline p = PipelineBuilder.from(new FileLineSource(in))
 *         .via(new ParserStage())
 *         .via(new PredicateFilterStage<>(r -> r.status() >= 400))
 *         .via(new ProjectionStage("timestamp", "clientIp", "status", "path"))
 *         .to(new FileSink<>(out));
 * }</pre>
 *
 * <p>{@code T}, şu ana kadarki zincirin çıktı tipidir. Bir aşama ancak girdisi {@code T}'yi
 * kabul ediyorsa eklenebilir; örneğin bir {@code LogRecord} filtresini ayrıştırıcıdan önce
 * koymak <b>derleme hatasıdır</b>. Her çağrı yeni bir builder döndürür (değişmez), böylece
 * ortak bir önek güvenle dallandırılabilir.
 */
public final class PipelineBuilder<T> {
    private final Source<?> source;
    private final List<Stage<?, ?>> stages;

    private PipelineBuilder(Source<?> source, List<Stage<?, ?>> stages) {
        this.source = source;
        this.stages = List.copyOf(stages);
    }

    public static <T> PipelineBuilder<T> from(Source<T> source) {
        return new PipelineBuilder<>(Objects.requireNonNull(source, "source"), List.of());
    }

    public <R> PipelineBuilder<R> via(Stage<? super T, R> stage) {
        List<Stage<?, ?>> next = new ArrayList<>(stages);
        next.add(Objects.requireNonNull(stage, "stage"));
        return new PipelineBuilder<>(source, next);
    }

    public Pipeline to(Sink<? super T> sink) {
        return new Pipeline(source, stages, Objects.requireNonNull(sink, "sink"));
    }
}
