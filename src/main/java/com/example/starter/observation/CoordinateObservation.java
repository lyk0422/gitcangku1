package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标观测落库行：对应 coordinate_observation 表一行。
 *
 * <p>原基准、原坐标与观测时刻来自请求；目标基准、目标坐标（六位小数 HALF_UP）与规则版本
 * 为入库时按观测时刻裁决后的冻结快照，之后规则调整不会重新解释本行。
 *
 * @param observationId 坐标观测业务唯一标识
 * @param requestId     首次写入该观测的请求标识（requestId）
 * @param sourceFrame   原坐标基准标识
 * @param originalX     原坐标 X（原基准长度单位）
 * @param originalY     原坐标 Y（原基准长度单位）
 * @param observedAtUtc 观测时刻（UTC）
 * @param targetFrame   命中规则的目标基准快照
 * @param targetX       转换后目标坐标 X（六位小数，目标基准长度单位）
 * @param targetY       转换后目标坐标 Y（六位小数，目标基准长度单位）
 * @param ruleVersion   命中的规则版本号
 * @param reviewStatus  复核状态：AUTO_MERGED / PENDING_REVIEW
 * @param autoMerged    是否已参加自动合并：仅站内容许行为 true
 */
public record CoordinateObservation(
        String observationId,
        String requestId,
        String sourceFrame,
        BigDecimal originalX,
        BigDecimal originalY,
        Instant observedAtUtc,
        String targetFrame,
        BigDecimal targetX,
        BigDecimal targetY,
        int ruleVersion,
        ReviewStatus reviewStatus,
        boolean autoMerged) {
}
