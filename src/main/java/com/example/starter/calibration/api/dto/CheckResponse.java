package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 期间核查提交/明细响应。
 * FAIL 时携带追溯区间与受影响（被标记 SUSPECT）的已放行测量键；
 * PASS 时携带本次解除的区间与恢复当前可用资格的测量键。
 *
 * @param checkKey            核查业务键
 * @param instrumentId        仪器 ID
 * @param checkedAt           UTC 核查时刻
 * @param standardValue       标准值（十进制字符串）
 * @param actualValue         实测值（十进制字符串）
 * @param tolerance           容差（十进制字符串）
 * @param deviation           |实测值 - 标准值|（十进制字符串，BigDecimal 精确计算）
 * @param result              判定结果：PASS / FAIL
 * @param checkedBy           核查人
 * @param createdAt           记录提交时间（UTC）
 * @param replayed            是否为 requestId 同参重放返回的首次结果
 * @param isolationRangeFrom  FAIL 追溯区间起点（UTC，含）；非 FAIL 为 null
 * @param isolationRangeTo    FAIL 追溯区间终点（UTC，不含）；非 FAIL 为 null
 * @param affectedKeys        FAIL 时被原子标记 SUSPECT 的已放行测量键
 * @param blockedPendingKeys  FAIL 时区间内被禁止放行的待放行测量键
 * @param resolvedIntervals   PASS 时本次解除的区间（触发 FAIL 的 checkKey 列表）
 * @param restoredKeys        PASS 时恢复当前可用资格的测量键（证书撤销或仍被其他 FAIL 覆盖者不在内）
 */
public record CheckResponse(
        String checkKey,
        String instrumentId,
        Instant checkedAt,
        String standardValue,
        String actualValue,
        String tolerance,
        String deviation,
        String result,
        String checkedBy,
        Instant createdAt,
        boolean replayed,
        Instant isolationRangeFrom,
        Instant isolationRangeTo,
        List<String> affectedKeys,
        List<String> blockedPendingKeys,
        List<String> resolvedIntervals,
        List<String> restoredKeys) {
}
