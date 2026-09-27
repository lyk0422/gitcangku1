package com.example.starter.batch.dto;

import java.time.Instant;
import java.util.List;

/**
 * 标签核销历史查询结果。时间窗 [from, to) 左闭右开（UTC）：from/to 为 null 表示该侧不限；
 * elapsedSeconds 为时间窗时长（to-from，两侧均给出时的实际秒数，否则为 null）；
 * eventCount 为窗内实际事件数。事件按发生时间升序、同刻按封箱版本号升序。
 */
public record PackingHistoryResponse(
        String batchKey,
        Instant from,
        Instant to,
        Long elapsedSeconds,
        int eventCount,
        List<PackingEventResponse> events
) {
}
