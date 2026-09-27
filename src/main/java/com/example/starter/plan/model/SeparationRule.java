package com.example.starter.plan.model;

/**
 * 危险品类别互斥规则：同一计划内类别对的车厢位置差不得小于最小间隔。
 * 类别对按字典序规范化存储（classA &lt; classB）。
 *
 * @param classA        字典序较小的类别代码
 * @param classB        字典序较大的类别代码
 * @param minSeparation 车厢位置差的最小要求值（车厢数），大于等于 1
 */
public record SeparationRule(String classA, String classB, int minSeparation) {
}
