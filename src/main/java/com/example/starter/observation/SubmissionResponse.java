package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测提交响应：原始值与提交时固化的维护标记一并返回，证据查询同样使用该结构显示维护标记。
 *
 * @param submissionId        提交唯一标识
 * @param stationId           所属观测站标识
 * @param observationId       归属观测记录标识
 * @param submittedAtUtc      观测提交时刻（UTC）
 * @param location            观测地点原始值
 * @param reading             观测读数原始值
 * @param note                观测备注原始值
 * @param maintenanceAffected 提交时是否命中维护窗口（MAINTENANCE_AFFECTED）
 * @param windowId            提交时命中窗口标识（固化）；未命中省略
 * @param windowVersion       提交时命中窗口维护版本号（固化）；未命中省略
 * @param status              当前处理状态：PENDING / REVIEW / CONSUMED
 * @param batchRequestId      所属批量提交 requestId
 */
public record SubmissionResponse(
        String submissionId,
        String stationId,
        String observationId,
        Instant submittedAtUtc,
        String location,
        String reading,
        String note,
        boolean maintenanceAffected,
        String windowId,
        Integer windowVersion,
        String status,
        String batchRequestId) {

    public static SubmissionResponse of(ObservationSubmission submission) {
        return new SubmissionResponse(
                submission.submissionId(), submission.stationId(), submission.observationId(),
                submission.submittedAtUtc(), submission.location(), submission.reading(), submission.note(),
                submission.maintenanceAffected(), submission.windowId(), submission.windowVersion(),
                submission.status().name(), submission.batchRequestId());
    }
}
