package com.example.starter.observation;

import java.time.Instant;

/**
 * 不可变重排记录：对应 observation_reorder 表的一行。
 * 偏移重建导致观测当前胜出版本变化时原子写入，固化设备、偏移变更、受影响观测与新旧顺序；永不修改。
 *
 * @param reorderId            重排记录唯一标识
 * @param observationId        受影响的观测记录标识
 * @param deviceId             触发重建的偏移记录所属设备标识
 * @param effectiveFromUtc     触发重建的偏移记录生效起始 UTC 时刻
 * @param oldOffsetSeconds     变更前偏移秒数；新增偏移记录时为 null
 * @param newOffsetSeconds     变更后偏移秒数
 * @param previousSubmissionId 重建前胜出提交标识
 * @param newSubmissionId      重建后胜出提交标识
 * @param previousOrderKey     重建前胜出提交顺序键（矫正时刻|设备标识|提交标识）
 * @param newOrderKey          重建后胜出提交顺序键
 * @param previousVersion      重建前观测当前版本号
 * @param newVersion           重建后观测当前版本号
 * @param requestId            触发重建的请求标识
 */
public record ObservationReorder(
        String reorderId,
        String observationId,
        String deviceId,
        Instant effectiveFromUtc,
        Integer oldOffsetSeconds,
        int newOffsetSeconds,
        String previousSubmissionId,
        String newSubmissionId,
        String previousOrderKey,
        String newOrderKey,
        int previousVersion,
        int newVersion,
        String requestId) {
}
