package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测提交原始记录：对应 observation_submission 表一行；维护标记按提交时窗口版本固化，后续不改写。
 *
 * @param submissionId       观测提交唯一标识
 * @param stationId          所属观测站标识
 * @param observationId      归属观测记录标识（冲突簇归并键）
 * @param submittedAtUtc     观测提交时刻（UTC），窗口匹配与簇内排序依据
 * @param location           观测地点原始值
 * @param reading            观测读数原始值（十进制原文）
 * @param note               观测备注原始值
 * @param maintenanceAffected 提交时是否命中维护窗口（固化）
 * @param windowId           提交时命中窗口标识（固化）；未命中为 null
 * @param windowVersion      提交时命中窗口维护版本号（固化）；未命中为 null
 * @param status             处理状态
 * @param batchRequestId     所属批量提交请求的 requestId
 */
public record ObservationSubmission(
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
        SubmissionStatus status,
        String batchRequestId) {
}
