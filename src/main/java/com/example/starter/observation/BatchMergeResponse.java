package com.example.starter.observation;

import java.util.List;

/**
 * 批量离线合并响应：整批全部落库后返回逐项结果与本批产生的冲突簇。
 *
 * @param requestId 批量请求标识（幂等重放原样返回）
 * @param items     逐项处理结果，顺序与请求一致
 * @param clusters  本批登记的 PENDING 冲突簇（无冲突时为空列表）
 */
public record BatchMergeResponse(
        String requestId,
        List<ItemResult> items,
        List<ClusterView> clusters) {

    /**
     * 单项结果。
     *
     * @param observationId 观测记录标识
     * @param created       是否为新接收观测（true：本批新建 RECEIVED；false：对已存在观测完成合并）
     * @param version       处理后当前版本号
     * @param observationStatus 处理后业务状态：RECEIVED（新建）/ MERGED（已合并，含结果一致未加版本）
     * @param conflict      该项是否产生冲突并登记 PENDING 簇
     * @param clusterId     冲突簇标识（无冲突时为 null）
     * @param permitId      该项命中的许可标识
     * @param permitVersion 该项命中许可的版本
     */
    public record ItemResult(
            String observationId,
            boolean created,
            int version,
            String observationStatus,
            boolean conflict,
            String clusterId,
            String permitId,
            String permitVersion) {
    }

    /**
     * 冲突簇视图。
     */
    public record ClusterView(
            String clusterId,
            String observationId,
            List<String> conflictFields,
            String status) {
    }
}
