package com.example.starter.frame;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 带版本的坐标基准转换规则：对应 coordinate_frame_rule 表的一行。
 *
 * <p>同一 ruleId 的版本按生效时间首尾相接（左闭右开），新版本发布时原开放版本自动闭合；
 * 不同 ruleId 的规则若在同一 UTC 时刻同时生效，观测写入将按"命中多条规则"返回 422。
 *
 * @param ruleId          规则链标识，同一基准转换规则的各版本共用
 * @param version         规则链内版本号，从 1 开始单调递增，观测结果落库时记录所用版本
 * @param targetDatum     转换目标基准名称
 * @param offsetX         X 方向偏移量（无单位约定，与原坐标同量纲），DECIMAL(18,9)
 * @param offsetY         Y 方向偏移量（无单位约定，与原坐标同量纲），DECIMAL(18,9)
 * @param effectiveFromUtc 生效起始时刻（UTC，含）
 * @param effectiveToUtc  生效结束时刻（UTC，不含）；null 表示开放区间，持续到被新版本闭合
 */
public record FrameRule(
        String ruleId,
        int version,
        String targetDatum,
        BigDecimal offsetX,
        BigDecimal offsetY,
        Instant effectiveFromUtc,
        Instant effectiveToUtc) {
}
