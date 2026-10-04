package com.example.starter.plan.model;

/**
 * 进路对称冲突对，构造时按字典序规范化（routeA <= routeB），语义双向。
 *
 * @param routeA 较小进路编号
 * @param routeB 较大进路编号
 */
public record ConflictPair(String routeA, String routeB) implements Comparable<ConflictPair> {

    public ConflictPair {
        if (routeA.compareTo(routeB) > 0) {
            String tmp = routeA;
            routeA = routeB;
            routeB = tmp;
        }
    }

    @Override
    public int compareTo(ConflictPair other) {
        int byA = routeA.compareTo(other.routeA);
        return byA != 0 ? byA : routeB.compareTo(other.routeB);
    }
}