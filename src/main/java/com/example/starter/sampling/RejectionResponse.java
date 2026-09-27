package com.example.starter.sampling;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 采样拒绝记录响应：返回可区分的拒绝原因。
 *
 * @param rejectionId    拒绝记录自增标识
 * @param scope          来源：SINGLE / BATCH
 * @param requestKey     提交幂等键（permitKey）或批次标识（batchId）
 * @param deviceId       观测设备标识
 * @param gridX          网格X坐标；许可级失败为 null
 * @param gridY          网格Y坐标；许可级失败为 null
 * @param collectedAtUtc 采集时刻（UTC）；许可级失败为 null
 * @param reason         拒绝原因码
 * @param message        拒绝详情
 * @param rejectedAtUtc  拒绝时刻（UTC）
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RejectionResponse(
        long rejectionId,
        String scope,
        String requestKey,
        String deviceId,
        Integer gridX,
        Integer gridY,
        Instant collectedAtUtc,
        String reason,
        String message,
        Instant rejectedAtUtc) {

    /**
     * 由拒绝记录构造响应。
     */
    public static RejectionResponse of(SamplingRejection rejection) {
        return new RejectionResponse(rejection.rejectionId(), rejection.scope(), rejection.requestKey(),
                rejection.deviceId(), rejection.gridX(), rejection.gridY(), rejection.collectedAtUtc(),
                rejection.reason(), rejection.message(), rejection.rejectedAtUtc());
    }
}
