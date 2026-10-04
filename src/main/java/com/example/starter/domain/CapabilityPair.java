package com.example.starter.domain;

/**
 * 一对互斥能力的无序集合：规范化后 first 字典序不大于 second，
 * 因此 (a, b) 与 (b, a) 视为同一对；能力相同不构成互斥（由调用方校验）。
 */
public record CapabilityPair(String first, String second) {

    public CapabilityPair {
        if (first == null || second == null || first.isBlank() || second.isBlank()) {
            throw new IllegalArgumentException("互斥能力名不能为空");
        }
        if (first.compareTo(second) > 0) {
            String swapped = first;
            first = second;
            second = swapped;
        }
    }

    /** 按无序语义构造互斥对。 */
    public static CapabilityPair of(String capabilityA, String capabilityB) {
        return new CapabilityPair(capabilityA, capabilityB);
    }
}