package logflow;

import java.util.ArrayList;
import java.util.List;

public class Pipeline {
    private final Source<?> source;
    private final List<Stage<?, ?>> stages;
    private final Sink<?> sink;

    public Pipeline(Source<?> source, List<Stage<?, ?>> stages, Sink<?> sink) {
        this.source = source;
        this.stages = new ArrayList<>(stages);
        this.sink = sink;
    }

    public Pipeline(Source<?> source, Sink<?> sink) {
        this(source, List.of(), sink);
    }

    /** Aşamaların değiştirilemez görünümü (sıra = veri akış sırası). */
    public List<Stage<?, ?>> stages() {
        return List.copyOf(stages);
    }

    public Source<?> source() {
        return source;
    }

    public Sink<?> sink() {
        return sink;
    }

    /**
     * Yaşam döngüsü: aşamaları ve sink'i açar, kaynağı akıtır, aşamaların {@code finish}
     * kancalarını sırayla çağırır, ardından
     * <b>her durumda</b> (istisna olsa bile) hepsini kapatır.
     */
    public void run() {
        List<Runnable> closers = new ArrayList<>();
        RuntimeException failure = null;
        try {
            for (Stage<?, ?> stage : stages) {
                stage.open();
                closers.add(stage::close);
            }
            sink.open();
            closers.add(sink::close);
            emitFromSource();
            finishStages();
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            for (Runnable closer : closers) {
                try {
                    closer.run();
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private void emitFromSource() {
        Source<Object> typedSource = (Source<Object>) source;
        typedSource.produce(item -> pushToStage(0, item));
    }

    /** Aşama i'nin bitişte yaydıkları yalnızca i+1..n aşamalarından geçer; sıra korunur. */
    @SuppressWarnings("unchecked")
    private void finishStages() {
        for (int i = 0; i < stages.size(); i++) {
            int next = i + 1;
            Stage<Object, Object> stage = (Stage<Object, Object>) stages.get(i);
            try {
                stage.finish(output -> pushToStage(next, output));
            } catch (StageException e) {
                throw new RuntimeException("Pipeline stage failed at index " + i + " while finishing", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void pushToStage(int index, Object item) {
        if (index >= stages.size()) {
            Sink<Object> typedSink = (Sink<Object>) sink;
            typedSink.consume(item);
            return;
        }

        Stage<Object, Object> stage = (Stage<Object, Object>) stages.get(index);

        try {
            stage.process(item, output -> pushToStage(index + 1, output));
        } catch (StageException e) {
            throw new RuntimeException("Pipeline stage failed at index " + index, e);
        }
    }
}
