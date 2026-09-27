package com.example.starter.maintenance.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 幂等指纹工具：以结构化、规范化 JSON 表达业务参数，不使用未转义分隔符拼接。
 * 字段名按字典序排列；集合元素先各自规范化再按规范化结果排序，消除提交顺序差异；
 * 有序列表（如批量条目在响应中的顺序）不属于集合语义，调用方应先转换为可比较的规范化结构。
 * null 字段保留其空值语义。
 */
public final class FingerprintUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private FingerprintUtil() {
    }

    /** 将任意可 JSON 化对象（record / Map / Collection / 基本类型 / Instant）规范化为稳定 JSON 字符串。 */
    public static String canonical(Object value) {
        try {
            Object normalized = normalize(value);
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("指纹规范化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
            return value;
        }
        if (value instanceof Instant instant) {
            return instant.toString();
        }
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, val) -> sorted.put(String.valueOf(key), normalize(val)));
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> elements = new ArrayList<>();
            for (Object element : collection) {
                elements.add(normalize(element));
            }
            elements.sort(Comparator.comparing(FingerprintUtil::writePlain));
            return elements;
        }
        // record 与普通 POJO：先转为 Map（null 字段默认保留），再按字典序规范化。
        Map<String, Object> pojoMap = MAPPER.convertValue(value, new TypeReference<Map<String, Object>>() {
        });
        return normalize(pojoMap);
    }

    private static String writePlain(Object normalized) {
        try {
            return MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            throw new IllegalStateException("指纹元素规范化失败", e);
        }
    }
}
