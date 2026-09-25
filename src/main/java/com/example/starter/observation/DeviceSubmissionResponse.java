package com.example.starter.observation;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 设备观测提交响应：返回原始本地时刻与矫正后时刻（二者均不可由客户端改写）、
 * 本次提交是否成为当前胜出提交，以及观测记录的当前版本号。
 *
 * @param submissionId   观测提交唯一标识
 * @param observationId  目标观测记录标识
 * @param deviceId       采集设备唯一标识
 * @param deviceLocalAt  设备本地时刻（原始值）
 * @param correctedAtUtc 矫正后时刻（UTC 时标，ISO-8601）
 * @param offsetSeconds  换算时命中的偏移秒数
 * @param applied        本次提交是否成为当前胜出提交并反映到观测当前内容；
 *                       查询列表时为 null；已有人工冲突解决结论时为 false
 * @param version        观测记录当前版本号；查询列表时为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeviceSubmissionResponse(
        String submissionId,
        String observationId,
        String deviceId,
        LocalDateTime deviceLocalAt,
        Instant correctedAtUtc,
        int offsetSeconds,
        Boolean applied,
        Integer version) {

    /**
     * 由提交记录构造查询响应（不含胜出与版本信息）。
     */
    public static DeviceSubmissionResponse of(DeviceSubmission submission) {
        return new DeviceSubmissionResponse(submission.submissionId(), submission.observationId(),
                submission.deviceId(), submission.deviceLocalAt(), submission.correctedAtUtc(),
                submission.offsetSeconds(), null, null);
    }
}
