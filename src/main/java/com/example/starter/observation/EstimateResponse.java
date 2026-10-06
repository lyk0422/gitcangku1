package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 分层样方密度估计成果响应：冻结的层全集、总体面积、成员身份与版本，以及精确结果。
 * 精确结果以约分有理数文本保存与返回；display 值（三位小数，HALF_UP）仅用于展示。
 *
 * @param estimateId   计算成果唯一标识
 * @param operator     操作者标识
 * @param layerCount   层数（2 至 5）
 * @param layers       冻结的层列表（按规范化顺序，与请求顺序无关）
 * @param totalExact   总体数量精确值（约分有理数文本，分母为 1 时为整数）
 * @param total        总体数量展示值（精确值 HALF_UP 三位小数）
 * @param createdAtUtc 成果生成时刻（UTC，ISO-8601）
 */
public record EstimateResponse(
        String estimateId,
        String operator,
        int layerCount,
        List<Layer> layers,
        String totalExact,
        String total,
        Instant createdAtUtc) {

    /**
     * 冻结的估计层。
     *
     * @param layerIndex    层序号（规范化排序后从 1 开始）
     * @param totalArea     该层总体面积（正整数，单位平方米）
     * @param countSum      该层成员观测计数总和
     * @param sampleAreaSum 该层成员采样面积总和（单位平方米）
     * @param densityExact  该层密度精确值（计数总和/采样面积总和，约分有理数文本）
     * @param density       该层密度展示值（HALF_UP 三位小数，不参与总量计算）
     * @param members       冻结的成员列表（按 observationId 排序）
     */
    public record Layer(
            int layerIndex,
            long totalArea,
            long countSum,
            long sampleAreaSum,
            String densityExact,
            String density,
            List<Member> members) {
    }

    /**
     * 冻结的层成员：生成成果时观测记录的版本与计数面积组。
     *
     * @param observationId 观测记录唯一标识
     * @param version       冻结的观测版本号
     * @param count         冻结的观测计数（非负整数）
     * @param sampleArea    冻结的采样面积（正整数，单位平方米）
     */
    public record Member(
            String observationId,
            int version,
            int count,
            int sampleArea) {
    }
}
