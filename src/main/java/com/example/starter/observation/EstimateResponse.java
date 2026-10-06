package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 密度估计成果响应：返回冻结的层、成员、版本与精确结果；展示值按 HALF_UP 保留三位小数。
 *
 * @param estimateId        成果唯一标识
 * @param requestId         生成该成果的请求标识
 * @param operator          操作者标识
 * @param layerCount        层数
 * @param observationCount  参与观测总数
 * @param resultExact       总体数量估计精确结果（最简分数，形如 500/3）
 * @param resultDisplay     展示值：精确结果按 HALF_UP 保留三位小数（不参与任何计算）
 * @param layers            冻结的层明细（按请求顺序）
 * @param createdAtUtc      成果生成时刻（UTC，ISO-8601）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EstimateResponse(
        String estimateId,
        String requestId,
        String operator,
        int layerCount,
        int observationCount,
        String resultExact,
        String resultDisplay,
        List<Layer> layers,
        Instant createdAtUtc) {

    /**
     * 由冻结记录组装响应。
     */
    public static EstimateResponse of(EstimateRecord record) {
        return new EstimateResponse(
                record.estimateId(),
                record.requestId(),
                record.operator(),
                record.layerCount(),
                record.observationCount(),
                record.resultNumerator() + "/" + record.resultDenominator(),
                record.resultDisplay(),
                record.layers().stream().map(EstimateResponse::layerOf).toList(),
                record.createdAtUtc());
    }

    private static Layer layerOf(EstimateRecord.EstimateLayerRecord layer) {
        return new Layer(
                layer.layerIndex(),
                layer.totalArea(),
                layer.densityNumerator() + "/" + layer.densityDenominator(),
                layer.members().stream().map(EstimateResponse::memberOf).toList());
    }

    private static Member memberOf(EstimateRecord.EstimateMemberRecord member) {
        return new Member(member.observationId(), member.observationVersion(),
                member.individualCount(), member.sampledArea());
    }

    /**
     * 冻结的估计层响应。
     *
     * @param layerIndex   层在请求中的顺序，从 0 开始
     * @param totalArea    该层总体面积，单位平方米
     * @param densityExact 该层精确密度（最简分数，单位：个体/平方米）
     * @param members      冻结的成员明细（按请求顺序）
     */
    public record Layer(
            int layerIndex,
            int totalArea,
            String densityExact,
            List<Member> members) {
    }

    /**
     * 冻结的估计成员响应。
     *
     * @param observationId   引用的观测记录标识
     * @param version         冻结的观测版本号
     * @param individualCount 冻结的个体计数
     * @param sampledArea     冻结的采样面积，单位平方米
     */
    public record Member(
            String observationId,
            int version,
            int individualCount,
            int sampledArea) {
    }
}
