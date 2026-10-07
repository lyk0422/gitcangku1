package com.example.starter.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 协调组允许元组的规范化文本编解码：用于锁命中依据的冻结存储与读回。
 *
 * <p>单条元组：{@code name=version} 按名称升序逗号连接；元组列表以分号连接。
 * 版本为十进制整数值，名称文本原样（不允许包含 {@code ,;=}，登记时已校验）。
 */
public final class CoordinationTuples {

    private CoordinationTuples() {
    }

    /** 单条元组 -> 规范化文本（成员按名称升序）。 */
    public static String canonicalTuple(Map<String, Integer> tuple) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(tuple).forEach((name, version) -> {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name).append('=').append(version);
        });
        return sb.toString();
    }

    /** 元组列表 -> 规范化文本（逐条 canonicalTuple 后以分号连接，保持给定顺序）。 */
    public static String canonicalTuples(List<Map<String, Integer>> tuples) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Integer> tuple : tuples) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(canonicalTuple(tuple));
        }
        return sb.toString();
    }

    /** 规范化文本 -> 单条元组（成员名 -> 版本，按名称升序）。 */
    public static Map<String, Integer> parseTuple(String canonical) {
        Map<String, Integer> tuple = new TreeMap<>();
        for (String pair : canonical.split(",")) {
            int eq = pair.lastIndexOf('=');
            tuple.put(pair.substring(0, eq), Integer.valueOf(pair.substring(eq + 1)));
        }
        return tuple;
    }

    /** 规范化文本 -> 元组列表。 */
    public static List<Map<String, Integer>> parseTuples(String canonical) {
        List<Map<String, Integer>> tuples = new ArrayList<>();
        for (String item : canonical.split(";")) {
            tuples.add(parseTuple(item));
        }
        return List.copyOf(tuples);
    }
}
