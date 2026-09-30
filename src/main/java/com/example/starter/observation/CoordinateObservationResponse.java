package com.example.starter.observation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标观测响应：同时给出原坐标、转换后目标坐标、目标基准、命中规则版本与复核状态。
 *
 * <p>目标坐标为入库时冻结的六位小数 HALF_UP 结果；之后规则调整不会改变本响应对应的历史结果。
 *
 * @param observationId 坐标观测业务唯一标识
 * @param sourceFrame   原坐标基准标识
 * @param originalX     原坐标 X（原基准长度单位）
 * @param originalY     原坐标 Y（原基准长度单位）
 * @param observedAtUtc 观测时刻（UTC）
 * @param targetFrame   转换时命中规则的目标基准
 * @param targetX       转换后目标坐标 X（六位小数，目标基准长度单位）
 * @param targetY       转换后目标坐标 Y（六位小数，目标基准长度单位）
 * @param ruleVersion   转换时命中的规则版本号
 * @param reviewStatus  复核状态：AUTO_MERGED（站内容许已自动合并）/ PENDING_REVIEW（站外待复核、不自动合并）
 * @param autoMerged    是否已参加自动合并
 */
public record CoordinateObservationResponse(
        String observationId,
        String sourceFrame,
        BigDecimal originalX,
        BigDecimal originalY,
        Instant observedAtUtc,
        String targetFrame,
        BigDecimal targetX,
        BigDecimal targetY,
        int ruleVersion,
        String reviewStatus,
        boolean autoMerged) {

    /**
     * 由落库行构造响应。
     */
    public static CoordinateObservationResponse of(CoordinateObservation observation) {
        return new CoordinateObservationResponse(
                observation.observationId(),
                observation.sourceFrame(),
                observation.originalX(),
                observation.originalY(),
                observation.observedAtUtc(),
                observation.targetFrame(),
                observation.targetX(),
                observation.targetY(),
                observation.ruleVersion(),
                observation.reviewStatus().name(),
                observation.autoMerged());
    }
}
