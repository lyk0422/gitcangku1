package com.example.starter.plan.model;

import java.util.List;

/**
 * 危险品互斥类别规则：同一编组内 classA 与 classB 两类危险品车厢之间，
 * 位置距离（|positionA - positionB|，单位：节车厢）必须不小于 {@code minSeparationCars}。
 *
 * @param classA             类别 A（规范化后小于 classB）
 * @param classB             类别 B（规范化后大于 classA）
 * @param minSeparationCars  要求的最小间隔车厢数，必须为正整数
 */
public record HazardRule(String classA, String classB, int minSeparationCars) {

    /**
     * 规范化为类别字母序的规则，保证 (A,B)/(B,A) 同参指纹与冲突对输出稳定。
     */
    public static HazardRule ordered(String classA, String classB, int minSeparationCars) {
        if (classA.compareTo(classB) <= 0) {
            return new HazardRule(classA, classB, minSeparationCars);
        }
        return new HazardRule(classB, classA, minSeparationCars);
    }

    /**
     * 判断两类别是否被本规则约束（类别对相同，与登记顺序无关）。
     */
    public boolean matches(String c1, String c2) {
        return classA.equals(c1) && classB.equals(c2) || classA.equals(c2) && classB.equals(c1);
    }

    /**
     * 规则集是否包含某类别（用于限制区段开放清单的类别合法性参考）。
     */
    public boolean involves(String hazardClass) {
        return classA.equals(hazardClass) || classB.equals(hazardClass);
    }

    /**
     * 规则集涉及的全部类别，规范化排序。
     */
    public List<String> classes() {
        return List.of(classA, classB);
    }
}
