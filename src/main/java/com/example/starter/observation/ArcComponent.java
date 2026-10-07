package com.example.starter.observation;

/**
 * 圆弧分量：方位误差区间（圆周区间）的原子字段组，起始角与弧长不可拆分，
 * 三方合并与裁定均按整组处理，绝不把一次提交的起始角与另一次提交的弧长拼组。
 *
 * <p>全部使用整数（千分之一度），禁止浮点：359 度与 1 度相距 2 度而非 358 度。
 *
 * @param startAngle 圆弧起始角，单位千分之一度，取值 0 至 359999
 * @param arcLength  正向弧长，单位千分之一度，取值 1 至 180000；区间含两端，按模 360000 绕回
 */
public record ArcComponent(Integer startAngle, Integer arcLength) {
}
