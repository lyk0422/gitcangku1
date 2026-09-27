package com.example.starter.maintenance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 证据包版本视图（不可变快照）。检查结果按模板 position 升序，字段、排序与 null 含义长期稳定。
 *
 * @param equipmentId      所属设备标识
 * @param cycleNo          所属周期号
 * @param packageVersion   证据包版本号，周期内从 1 递增
 * @param templateVersion  提交时固化的模板版本
 * @param submittedBy      证据提交人
 * @param submittedAt      提交时刻（UTC）
 * @param requestId        产生该证据包的请求 requestId
 * @param checks           检查项结果（按 position 升序）
 * @param materials        材料批次摘要（按 materialId 升序，稳定排序）
 * @param equipmentVersion 操作后的设备版本号（历史查询时为当前版本）
 */
public record EvidencePackageResponse(
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int templateVersion,
        String submittedBy,
        Instant submittedAt,
        String requestId,
        List<CheckResultView> checks,
        List<MaterialSummaryView> materials,
        long equipmentVersion) {

    /**
     * 检查项结果视图。
     *
     * @param position  规范排序位置
     * @param itemCode  检查项编码
     * @param result    PASS 或 FAIL
     * @param note      检查备注，null 表示无备注
     */
    public record CheckResultView(
            int position,
            String itemCode,
            String result,
            String note) {
    }

    /**
     * 材料批次摘要视图。
     *
     * @param materialId      材料批次号
     * @param quantity        实际用量
     * @param unit            计量单位（提交时批次登记单位的快照）
     * @param packageVersion  首次引用该批次的证据包版本号
     */
    public record MaterialSummaryView(
            String materialId,
            BigDecimal quantity,
            String unit,
            int packageVersion) {
    }
}
