package com.example.starter.plan.model;

/**
 * 危险品互斥类别隔离规则：两类车厢在编组内至少相隔 minSeparation 个车厢位置。
 * 类别对按字典序规范化存储（categoryA &lt; categoryB）。
 *
 * @param id            主键
 * @param categoryA     类别对中字典序较小者
 * @param categoryB     类别对中字典序较大者
 * @param minSeparation 要求的最小车厢位置间隔，非负
 */
public record SeparationRule(long id, String categoryA, String categoryB, int minSeparation) {
}
