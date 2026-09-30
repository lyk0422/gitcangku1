package com.example.starter.frame;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 坐标观测响应：同时给出原坐标、目标坐标、规则版本与复核状态。
 *
 * @param coordinateObservationId 坐标观测业务标识
 * @param sourceDatum             原基准名称
 * @param sourceX                 原 X 坐标
 * @param sourceY                 原 Y 坐标
 * @param observedAtUtc           观测时刻（UTC）
 * @param targetDatum             目标基准名称（取自命中规则）
 * @param targetX                 目标 X 坐标（六位小数 HALF_UP）
 * @param targetY                 目标 Y 坐标（六位小数 HALF_UP）
 * @param ruleId                  命中的规则链标识
 * @param ruleVersion             命中的规则版本号
 * @param reviewStatus            复核状态：AUTO_MERGED（站内，已自动合并）/ PENDING_REVIEW（站外，待复核）
 */
public record CoordinateObservationResponse(
        String coordinateObservationId,
        String sourceDatum,
        BigDecimal sourceX,
        BigDecimal sourceY,
        Instant observedAtUtc,
        String targetDatum,
        BigDecimal targetX,
        BigDecimal targetY,
        String ruleId,
        int ruleVersion,
        ReviewStatus reviewStatus) {

    /**
     * 由落库结果构造响应；原坐标按数值回显。
     */
    public static CoordinateObservationResponse of(CoordinateObservation observation) {
        return new CoordinateObservationResponse(
                observation.coordinateObservationId(),
                observation.sourceDatum(),
                new BigDecimal(observation.sourceX()),
                new BigDecimal(observation.sourceY()),
                observation.observedAtUtc(),
                observation.targetDatum(),
                observation.targetX(),
                observation.targetY(),
                observation.ruleId(),
                observation.ruleVersion(),
                observation.reviewStatus());
    }
}
