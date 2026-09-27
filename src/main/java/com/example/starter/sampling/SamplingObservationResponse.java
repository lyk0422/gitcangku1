package com.example.starter.sampling;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 采样观测查询响应：携带命中许可版本与复核标记。
 *
 * @param permitKey      提交幂等键
 * @param deviceId       观测设备标识
 * @param gridX          网格X坐标
 * @param gridY          网格Y坐标
 * @param collectedAtUtc 采集时刻（UTC）
 * @param content        观测内容
 * @param permitId       命中的许可标识
 * @param permitVersion  命中许可的版本号
 * @param status         观测状态：RECEIVED / MERGED / PERMIT_REVIEW
 * @param reviewFlag     复核标记：许可撤销后待人工复核为 true
 * @param batchId        并入的批量合并标识；单次提交为 null
 * @param clusterId      所属冲突簇标识；无冲突为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SamplingObservationResponse(
        String permitKey,
        String deviceId,
        int gridX,
        int gridY,
        Instant collectedAtUtc,
        String content,
        String permitId,
        int permitVersion,
        String status,
        boolean reviewFlag,
        String batchId,
        String clusterId) {

    /**
     * 由观测记录构造响应；PERMIT_REVIEW 状态置复核标记。
     */
    public static SamplingObservationResponse of(SamplingObservation observation) {
        return new SamplingObservationResponse(
                observation.permitKey(), observation.deviceId(), observation.gridX(), observation.gridY(),
                observation.collectedAtUtc(), observation.content(),
                observation.permitId(), observation.permitVersion(),
                observation.status().name(), observation.status() == ObservationStatus.PERMIT_REVIEW,
                observation.batchId(), observation.clusterId());
    }
}
