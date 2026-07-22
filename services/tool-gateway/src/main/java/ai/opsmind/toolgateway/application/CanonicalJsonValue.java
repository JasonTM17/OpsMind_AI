package ai.opsmind.toolgateway.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Recursively orders JSON object keys before cryptographic digest serialization. */
final class CanonicalJsonValue {

    private CanonicalJsonValue() { }

    static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> ordered = new TreeMap<>();
            map.forEach((key, nested) -> ordered.put(String.valueOf(key), normalize(nested)));
            return ordered;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            list.forEach(item -> normalized.add(normalize(item)));
            return List.copyOf(normalized);
        }
        return value;
    }
}
