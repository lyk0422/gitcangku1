package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 漂移修正区间内单条读数的新旧工时视图。
 *
 * @param readingId     读数标识
 * @param sampledAt     UTC 采样时刻
 * @param anchor        是否为锚点读数
 * @param frozen        是否已被已完成保养记录冻结（冻结点落在集合内则整单不可激活）
 * @param segmentIndex  插值段序号（首尾锚点间第 1 段从 1 开始）；锚点为 null
 * @param oldRevisionNo 修正前修订号
 * @param newRevisionNo 激活后新修订号（预览时为拟生成的新版本号）
 * @param oldHours      修正前累计工时（小时，未修正读数由分钟精确换算）
 * @param newHours      修正后累计工时（小时，3 位小数）
 */
public record DriftReadingView(
        String readingId,
        Instant sampledAt,
        boolean anchor,
        boolean frozen,
        Integer segmentIndex,
        int oldRevisionNo,
        int newRevisionNo,
        BigDecimal oldHours,
        BigDecimal newHours) {
}
