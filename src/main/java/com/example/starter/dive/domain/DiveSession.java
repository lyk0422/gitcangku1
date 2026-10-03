package com.example.starter.dive.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 潜水作业（训练计划与点位版本）。
 *
 * @param id              作业ID
 * @param name            作业名称（合成训练数据）
 * @param plannedStartUtc 计划作业开始时刻（UTC），首点须不晚于该时刻
 * @param plannedEndUtc   计划作业结束时刻（UTC），末点须覆盖到该时刻
 * @param maxDepthM       计划最大深度（米），审核阈值含边界
 * @param maxDurationMin  连续作业上限（分钟），按首末点间隔计算
 * @param pointVersion    点位版本号，每次追加点位批次后递增，审核按版本冻结
 * @param createdAtUtc    记录创建时刻（UTC）
 */
public record DiveSession(
        long id,
        String name,
        Instant plannedStartUtc,
        Instant plannedEndUtc,
        BigDecimal maxDepthM,
        int maxDurationMin,
        int pointVersion,
        Instant createdAtUtc) {
}