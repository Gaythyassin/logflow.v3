package logflow.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import logflow.Pipeline;
import logflow.Sink;
import logflow.Source;
import logflow.Stage;

/**
 * Yapılandırma dosyasını okur, her bileşenin gerçekleştirimini kayıtlardan bulur,
 * parametreleri doğrular, aşama türlerinin birbirine uyduğunu denetler ve bir {@link Pipeline} kurar.
 *
 * <p><b>Hızlı ve yüksek sesle başarısız ol:</b> bütün denetimler tek bir veri satırı okunmadan
 * yapılır. Bir ya da daha fazla sorun varsa, hepsini listeleyen bir {@link ConfigException}
 * fırlatılır; hiçbir dosya açılmaz, hiçbir çıktı yazılmaz.
 */
public final class PipelineLoader {
    private final Plugins plugins;

    public PipelineLoader(Plugins plugins) {
        this.plugins = plugins;
    }

    /** Sınıf yolundaki bütün bileşenlerle. */
    public PipelineLoader() {
        this(Plugins.discover());
    }

    public LoadedPipeline load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (NoSuchFileException e) {
            throw new ConfigException(file.toString(), "config file not found: " + file.toAbsolutePath());
        } catch (IOException e) {
            throw new ConfigException(file.toString(), "cannot read config file: " + e.getMessage());
        }
        return load(text, file.toString());
    }

    /** @param sourceName mesajlarda gösterilecek ad */
    public LoadedPipeline load(String yaml, String sourceName) {
        List<String> problems = new ArrayList<>();
        PipelineSpec spec = PipelineSpec.from(YamlParser.parse(yaml, sourceName), problems);
        if (spec == null) {
            throw new ConfigException(sourceName, problems);
        }
        return build(spec, sourceName);
    }

    /** Tanımı ({@link PipelineSpec}) gerçekleştirime çevirir. */
    public LoadedPipeline build(PipelineSpec spec, String sourceName) {
        List<String> problems = new ArrayList<>();

        SourceFactory sourceFactory = resolve(plugins.sources(), spec.source(), problems);
        Source<?> source = create(sourceFactory, spec.source(), sourceFactory == null ? null : sourceFactory::create, problems);
        Class<?> current = sourceFactory == null ? null : sourceFactory.outputType();
        String producer = "the source '" + spec.source().type() + "'";

        List<Stage<?, ?>> stages = new ArrayList<>();
        for (ComponentSpec stageSpec : spec.stages()) {
            StageFactory factory = resolve(plugins.stages(), stageSpec, problems);
            if (factory == null) {
                current = null; // sonraki aşamalar için tür bilinmiyor; sahte hata üretme
                continue;
            }
            checkFlow(stageSpec, factory.inputType(), current, producer, problems);
            stages.add(create(factory, stageSpec, factory::create, problems));
            current = factory.outputType();
            producer = stageSpec.label();
        }

        SinkFactory sinkFactory = resolve(plugins.sinks(), spec.sink(), problems);
        Sink<?> sink = null;
        if (sinkFactory != null) {
            checkFlow(spec.sink(), sinkFactory.inputType(), current, producer, problems);
            sink = create(sinkFactory, spec.sink(), sinkFactory::create, problems);
        }

        if (!problems.isEmpty()) {
            throw new ConfigException(sourceName, problems);
        }
        return new LoadedPipeline(spec.name(), sourceName, new Pipeline(source, stages, sink));
    }

    private <F extends ComponentFactory> F resolve(Registry<F> registry, ComponentSpec spec, List<String> problems) {
        F factory = registry.find(spec.type()).orElse(null);
        if (factory == null) {
            problems.add(spec.location() + ".type: " + registry.unknownTypeMessage(spec.type()));
        }
        return factory;
    }

    /** Parametreleri doğrular, geçerliyse bileşeni kurar; fabrikanın anlamsal itirazları da sorun olarak toplanır. */
    private <T> T create(ComponentFactory factory, ComponentSpec spec, Function<Params, T> constructor, List<String> problems) {
        if (factory == null) {
            return null;
        }
        Params params = Params.validate(spec.label(), spec.params(), factory.params(), problems);
        if (params == null) {
            return null;
        }
        try {
            return constructor.apply(params);
        } catch (IllegalArgumentException e) {
            problems.add(spec.label() + ": " + e.getMessage());
            return null;
        }
    }

    private void checkFlow(ComponentSpec spec, Class<?> expected, Class<?> actual, String producer, List<String> problems) {
        if (actual == null || expected.isAssignableFrom(actual)) {
            return;
        }
        List<String> converters = plugins.stages().converters(actual, expected);
        problems.add(spec.label() + ": expects " + expected.getSimpleName() + " records, but " + producer
                + " produces " + actual.getSimpleName()
                + (converters.isEmpty() ? "." : ". Add a " + String.join(" or ", converters.stream().map(c -> "'" + c + "'").toList())
                        + " stage before it."));
    }
}
