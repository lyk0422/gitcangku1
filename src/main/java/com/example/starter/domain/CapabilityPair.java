package com.example.starter.domain;

/**
 * 互斥能力对：无序集合，内部按字典序规范化存储（first &lt; second）。
 * 能力相同不构成互斥，反向登记视为同一对。
 */
public record CapabilityPair(String first, String second) {

    public CapabilityPair {
        if (first == null || second == null || first.isBlank() || second.isBlank()) {
            throw new IllegalArgumentException("能力名不能为空");
        }
        if (first.compareTo(second) > 0) {
            String tmp = first;
            first = second;
            second = tmp;
        }
    }

    /** 以任意顺序构造互斥对，自动规范化为字典序。 */
    public static CapabilityPair of(String a, String b) {
        return new CapabilityPair(a, b);
    }

    /** 两个能力名是否构成同一对（与顺序无关）。 */
    public boolean matches(String a, String b) {
        return (first.equals(a) && second.equals(b)) || (first.equals(b) && second.equals(a));
    }
}