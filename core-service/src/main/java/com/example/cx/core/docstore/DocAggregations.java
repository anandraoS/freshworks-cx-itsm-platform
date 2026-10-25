package com.example.cx.core.docstore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** JVM-side fold used by the non-Mongo stores (same result shape as the Mongo $group/$avg pipeline). */
final class DocAggregations {
    private DocAggregations() {}

    static Map<String, Double> averageBy(List<Map<String, Object>> docs, String groupField, String valueField) {
        Map<String, double[]> acc = new TreeMap<>();
        for (Map<String, Object> d : docs) {
            Object g = d.get(groupField), v = d.get(valueField);
            if (g == null || !(v instanceof Number n)) continue;
            double[] a = acc.computeIfAbsent(String.valueOf(g), k -> new double[2]);
            a[0] += n.doubleValue();
            a[1]++;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        acc.forEach((k, a) -> out.put(k, a[0] / a[1]));
        return out;
    }
}
