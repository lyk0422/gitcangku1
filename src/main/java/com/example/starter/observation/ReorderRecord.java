package com.example.starter.observation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 不可变重排记录：对应 observation_reorder 表的一行。
 * 偏移登记/修改触发的重建使某观测当前胜出版本变化时写入，永不更新或删除。
 *
 * @param reorderId        重排记录标识：触发请求标识#观测标识
 * @param requestId        触发本次重建的偏移变更请求标识
 * @param deviceId         偏移变更所属设备标识
 * @param effectiveFromUtc 变更的偏移记录生效起始时刻（UTC）
 * @param oldOffsetSeconds 变更前偏移秒数；新增偏移记录时为 null
 * @param newOffsetSeconds 变更后偏移秒数
 * @param observationId    受影响的观测标识
 * @param oldWinnerVersion 重建前当前胜出版本号
 * @param newWinnerVersion 重建后当前胜出版本号
 * @param oldOrder         重建前该观测各版本按合并排位的版本号列表
 * @param newOrder         重建后该观测各版本按合并排位的版本号列表
 */
public record ReorderRecord(
        String reorderId,
        String requestId,
        String deviceId,
        Instant effectiveFromUtc,
        Integer oldOffsetSeconds,
        int newOffsetSeconds,
        String observationId,
        int oldWinnerVersion,
        int newWinnerVersion,
        List<Integer> oldOrder,
        List<Integer> newOrder) {
}
