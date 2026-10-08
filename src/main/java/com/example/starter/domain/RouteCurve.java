package com.example.starter.domain;

import java.util.List;

/**
 * 由若干二次曲线段组成的航线。
 *
 * <p>段数 1~5，段按顺序首尾相接：第 i 段的固定终点 P2 必须等于
 * 第 i+1 段的固定起点 P0；控制点允许退化（共线或与端点重合）。</p>
 *
 * @param segments 有序二次曲线段，第 i 段 P2 与第 i+1 段 P0 为同一点
 */
public record RouteCurve(List<QuadraticCurve> segments) {
}
