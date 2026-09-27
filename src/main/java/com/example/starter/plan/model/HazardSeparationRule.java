package com.example.starter.plan.model;

/**
 * 互斥危险品类别隔离规则。
 *
 * @param configVersionId 所属配置版本 id
 * @param classA          互斥类别对中字典序较小的类别
 * @param classB          互斥类别对中字典序较大的类别
 * @param minCars         两车厢之间至少相隔的车厢数（实际相隔 = 位置差 - 1）
 */
public record HazardSeparationRule(long configVersionId, String classA, String classB, int minCars) {
}
