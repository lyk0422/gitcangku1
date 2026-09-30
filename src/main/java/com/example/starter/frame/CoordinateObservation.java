package com.example.starter.frame;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标观测落库结果：对应 coordinate_observation 表的一行。
 *
 * <p>目标基准、目标坐标与规则版本均在接收事务内按提交时生效的规则一次性计算并固化，
 * 之后规则调整不会重新解释该记录（历史结果不变）。
 *
 * @param coordinateObservationId 坐标观测业务标识
 * @param sourceDatum             观测携带的原基准名称
 * @param sourceX                 原 X 坐标（十进制原文，按请求入参保存）
 * @param sourceY                 原 Y 坐标（十进制原文，按请求入参保存）
 * @param observedAtUtc           观测时刻（UTC），规则选择依据
 * @param targetDatum             计算时所用规则的目标基准名称
 * @param targetX                 目标 X 坐标 = 原 X + 规则 X 偏移，六位小数 HALF_UP，DECIMAL(18,6)
 * @param targetY                 目标 Y 坐标 = 原 Y + 规则 Y 偏移，六位小数 HALF_UP，DECIMAL(18,6)
 * @param ruleId                  计算时命中的规则链标识
 * @param ruleVersion             计算时命中的规则版本号
 * @param reviewStatus            复核状态：站内自动合并 / 站外待复核
 */
public record CoordinateObservation(
        String coordinateObservationId,
        String sourceDatum,
        String sourceX,
        String sourceY,
        Instant observedAtUtc,
        String targetDatum,
        BigDecimal targetX,
        BigDecimal targetY,
        String ruleId,
        int ruleVersion,
        ReviewStatus reviewStatus) {
}
