package com.example.starter.race.api;

/**
 * 暂停区间内到达的终点计时事件诊断项。
 *
 * @param holdId       落入的医疗暂停ID
 * @param startAt      暂停开始时刻，Unix毫秒时间戳（UTC）
 * @param endAt        暂停结束时刻，Unix毫秒时间戳（UTC）；暂停仍生效为 null（右端点未定时，事件不早于startAt即落入）
 * @param finishTimeMs 该终点事件的原始完赛耗时（毫秒）
 * @param requestId    产生该终点事件的写操作 requestId
 * @param createdAt    终点计时提交时刻，Unix毫秒时间戳（UTC）
 */
public record FinishEventInHoldResponse(
        String holdId,
        long startAt,
        Long endAt,
        long finishTimeMs,
        String requestId,
        long createdAt
) {
}
