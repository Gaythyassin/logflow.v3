package logflow.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Yapılandırmadaki bir bileşenin tanımı (mimari tanım): yalnızca veri, gerçekleştirim yok.
 *
 * @param location hata mesajları için konum, ör. {@code pipeline.stages[1]}
 */
public record ComponentSpec(String location, String type, Map<String, Object> params) {

    public ComponentSpec {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** {@code pipeline.stages[1] (filter)} */
    public String label() {
        return location + " (" + type + ")";
    }
}
