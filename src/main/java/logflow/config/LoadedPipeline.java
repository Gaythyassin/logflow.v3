package logflow.config;

import java.util.ArrayList;
import java.util.List;
import logflow.Pipeline;
import logflow.Reportable;

/** Yapılandırmadan kurulmuş, çalışmaya hazır bir pipeline ve adı. */
public record LoadedPipeline(String name, String source, Pipeline pipeline) {

    public void run() {
        pipeline.run();
    }

    /** Çalıştıktan sonra, özet verebilen her bileşenden bir satır (akış sırasıyla). */
    public List<String> report() {
        List<Object> components = new ArrayList<>();
        components.add(pipeline.source());
        components.addAll(pipeline.stages());
        components.add(pipeline.sink());
        return components.stream().filter(c -> c instanceof Reportable).map(c -> ((Reportable) c).report()).toList();
    }
}
