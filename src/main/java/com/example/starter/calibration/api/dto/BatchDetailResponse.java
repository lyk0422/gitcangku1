package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 批次明细响应：当前修订配置、读数、离群、统计与修订/事件历史。读取接口不改变任何状态。
 *
 * @param batch          批次基本信息
 * @param revision       当前修订版本
 * @param revisions      全部修订历史（按版本号升序）
 * @param readings       当前修订下全部读数（含已排除，按提交顺序）
 * @param outliers       当前修订下全部离群记录（按检测顺序）
 * @param statistics     当前修订重算后的统计（实际值/要求值/差额）
 * @param events         批次不可变事件流水（按提交顺序）
 */
public record BatchDetailResponse(
        BatchResponse batch,
        RevisionResponse revision,
        List<RevisionResponse> revisions,
        List<MeasurementResponse> readings,
        List<OutlierResponse> outliers,
        BatchStatisticsResponse statistics,
        List<BatchEventResponse> events) {

    /**
     * 批次统计响应。
     *
     * @param submittedTotal         提交读数总数（实际值）
     * @param excludedCount          已排除数量
     * @param activeCount            参与统计/放行数量（实际值）
     * @param outlierTotal           离群总数
     * @param pendingOutlierCount    待裁决（含待仲裁）离群数；要求值为 0
     * @param acceptedOutlierCount   同议 ACCEPT 数
     * @param rejectedOutlierCount   同议 REJECT 数
     * @param requiredPendingOutliers 放行要求的待裁决数（要求值，恒为 0）
     * @param pendingOutlierGap      距放行差额=待裁决数-要求值（不为负）
     * @param releaseBlocked         是否因未裁决离群阻断放行
     * @param activeMean             活动读数未舍入值均值（无活动读数为 null）
     * @param activeMin              活动读数最小值（无活动读数为 null）
     * @param activeMax              活动读数最大值（无活动读数为 null）
     * @param computedAt             统计计算时刻（UTC）
     */
    public record BatchStatisticsResponse(
            int submittedTotal,
            int excludedCount,
            int activeCount,
            int outlierTotal,
            int pendingOutlierCount,
            int acceptedOutlierCount,
            int rejectedOutlierCount,
            int requiredPendingOutliers,
            int pendingOutlierGap,
            boolean releaseBlocked,
            String activeMean,
            String activeMin,
            String activeMax,
            Instant computedAt) {
    }

    /**
     * 批次事件流水响应。payload 为封存 JSON 原文，字段与 null 含义长期稳定。
     *
     * @param seq        事件序号（提交顺序）
     * @param revisionNo 关联修订版本号
     * @param eventType  事件类型
     * @param actor      触发角色
     * @param payload    封存内容 JSON 原文
     * @param occurredAt 事件发生时间（UTC）
     */
    public record BatchEventResponse(
            long seq,
            int revisionNo,
            String eventType,
            String actor,
            String payload,
            Instant occurredAt) {
    }
}
