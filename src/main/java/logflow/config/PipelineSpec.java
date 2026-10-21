package logflow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Yapılandırma dosyasının yapısal okuması: hangi bileşenler, hangi sırayla, hangi parametrelerle.
 * Henüz hiçbir sınıf seçilmez; bu, mimari tanımı gerçekleştirimden ayıran katmandır.
 *
 * <pre>
 * pipeline:
 *   name: error-report
 *   source: { type: file, params: { path: data/access-small.log } }
 *   stages:
 *     - type: parse
 *     - type: filter
 *       params: { field: status, op: "&gt;=", value: 400 }
 *   sink: { type: file, params: { path: out/errors.txt } }
 * </pre>
 */
public record PipelineSpec(String name, String description, ComponentSpec source, List<ComponentSpec> stages, ComponentSpec sink) {

    static final List<String> ROOT_KEYS = List.of("pipeline");
    static final List<String> PIPELINE_KEYS = List.of("name", "description", "source", "stages", "sink");
    static final List<String> COMPONENT_KEYS = List.of("type", "params");

    public PipelineSpec {
        stages = List.copyOf(stages);
    }

    /**
     * Ayrıştırılmış YAML'dan tanımı çıkarır; yapısal sorunları {@code problems} listesine ekler.
     *
     * @return yapı geçerliyse tanım, değilse {@code null}
     */
    static PipelineSpec from(Object root, List<String> problems) {
        if (root == null) {
            problems.add("the configuration is empty; expected a top-level 'pipeline:' section");
            return null;
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            problems.add("the top level must be a mapping with a 'pipeline:' section, got " + ParamType.describe(root));
            return null;
        }
        unknownKeys("top level", rootMap, ROOT_KEYS, problems);
        Object p = rootMap.get("pipeline");
        if (p == null) {
            if (rootMap.containsKey("pipeline")) {
                problems.add("pipeline: the pipeline is empty; it needs 'name', 'source', 'stages' and 'sink'");
            } else {
                problems.add("missing top-level 'pipeline:' section");
            }
            return null;
        }
        if (!(p instanceof Map<?, ?> pipeline)) {
            problems.add("pipeline: must be a mapping, got " + ParamType.describe(p));
            return null;
        }
        int before = problems.size();
        unknownKeys("pipeline", pipeline, PIPELINE_KEYS, problems);

        String name = string(pipeline, "name", "pipeline.name", true, problems);
        String description = string(pipeline, "description", "pipeline.description", false, problems);
        ComponentSpec source = component(pipeline.get("source"), "pipeline.source", problems);
        ComponentSpec sink = component(pipeline.get("sink"), "pipeline.sink", problems);

        List<ComponentSpec> stages = new ArrayList<>();
        Object s = pipeline.get("stages");
        if (s == null) {
            problems.add("pipeline.stages: missing; the pipeline needs at least one stage, e.g. '- type: parse'");
        } else if (!(s instanceof List<?> list)) {
            problems.add("pipeline.stages: must be a list ('- type: ...' items), got " + ParamType.describe(s));
        } else if (list.isEmpty()) {
            problems.add("pipeline.stages: the pipeline is empty; add at least one stage, e.g. '- type: parse'");
        } else {
            for (int i = 0; i < list.size(); i++) {
                stages.add(component(list.get(i), "pipeline.stages[" + i + "]", problems));
            }
        }
        return problems.size() == before ? new PipelineSpec(name, description, source, stages, sink) : null;
    }

    private static ComponentSpec component(Object value, String location, List<String> problems) {
        if (value == null) {
            problems.add(location + ": missing; expected '{ type: ..., params: { ... } }'");
            return null;
        }
        if (!(value instanceof Map<?, ?> map)) {
            problems.add(location + ": must be a mapping with 'type' and optional 'params', got " + ParamType.describe(value));
            return null;
        }
        unknownKeys(location, map, COMPONENT_KEYS, problems);
        String type = string(map, "type", location + ".type", true, problems);
        Object params = map.get("params");
        if (params != null && !(params instanceof Map<?, ?>)) {
            problems.add(location + ".params: must be a mapping like '{ name: value }', got " + ParamType.describe(params));
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) params;
        return type == null ? null : new ComponentSpec(location, type, typed);
    }

    private static String string(Map<?, ?> map, String key, String location, boolean required, List<String> problems) {
        Object v = map.get(key);
        if (v == null) {
            if (required) {
                problems.add(location + ": missing (string)");
            }
            return null;
        }
        if (!(v instanceof String s) || s.isBlank()) {
            problems.add(location + ": must be a non-empty string, got " + ParamType.describe(v));
            return null;
        }
        return s;
    }

    private static void unknownKeys(String location, Map<?, ?> map, List<String> known, List<String> problems) {
        for (Object k : map.keySet()) {
            if (!known.contains(String.valueOf(k))) {
                problems.add(location + ": unknown key '" + k + "'." + Suggestions.didYouMean(String.valueOf(k), known)
                        + " Allowed keys: " + known);
            }
        }
    }
}
