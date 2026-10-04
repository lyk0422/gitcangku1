package com.example.starter.plan.model;

/**
 * 进路对称冲突对，按字典序规范化存储（routeA &lt; routeB），任一方向语义等价。
 *
 * @param routeA 字典序较小的进路编号
 * @param routeB 字典序较大的进路编号
 */
public record RoutePair(String routeA, String routeB) {

    /**
     * 规范化构造：任意顺序的两个进路编号归一为 (小, 大)。
     */
    public static RoutePair of(String first, String second) {
        if (first.compareTo(second) <= 0) {
            return new RoutePair(first, second);
        }
        return new RoutePair(second, first);
    }
}