package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 不可变密度估计成果记录：对应 density_estimate 主表及冻结的层与成员明细，成功生成后永不修改。
 *
 * @param estimateId         成果唯一标识
 * @param requestId          生成该成果的请求标识
 * @param operator           操作者标识
 * @param layerCount         层数（2至5）
 * @param observationCount   参与观测总数（不超过20）
 * @param resultNumerator    总体数量估计精确结果的分子（最简分数，十进制整数字符串）
 * @param resultDenominator  总体数量估计精确结果的分母（最简分数，正整数十进制字符串）
 * @param resultDisplay      展示值：精确结果按 HALF_UP 保留三位小数
 * @param createdAtUtc       成果生成时刻（UTC）
 * @param layers             冻结的层明细（按请求顺序）
 */
public record EstimateRecord(
        String estimateId,
        String requestId,
        String operator,
        int layerCount,
        int observationCount,
        String resultNumerator,
        String resultDenominator,
        String resultDisplay,
        Instant createdAtUtc,
        List<EstimateLayerRecord> layers) {

    /**
     * 冻结的估计层：对应 density_estimate_layer 一行及其成员明细。
     *
     * @param layerIndex         层在请求中的顺序，从 0 开始
     * @param totalArea          该层总体面积，正整数，单位平方米
     * @param densityNumerator   该层精确密度的分子（最简分数），单位：个体/平方米
     * @param densityDenominator 该层精确密度的分母（最简分数，正整数）
     * @param members            冻结的成员明细（按请求顺序）
     */
    public record EstimateLayerRecord(
            int layerIndex,
            int totalArea,
            String densityNumerator,
            String densityDenominator,
            List<EstimateMemberRecord> members) {
    }

    /**
     * 冻结的估计成员：对应 density_estimate_member 一行，保存生成时核对的观测版本与计数面积组。
     *
     * @param memberIndex        成员在层内的顺序，从 0 开始
     * @param observationId      引用的观测记录标识
     * @param observationVersion 生成成果时核对并冻结的观测当前版本号
     * @param individualCount    冻结的个体计数（非负整数）
     * @param sampledArea        冻结的采样面积（正整数，单位平方米）
     */
    public record EstimateMemberRecord(
            int memberIndex,
            String observationId,
            int observationVersion,
            int individualCount,
            int sampledArea) {
    }
}
