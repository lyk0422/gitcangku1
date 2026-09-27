package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单完整性事件查询响应（只读）。时间区间为 UTC、左闭右开 [fromUtc, toUtc)，
 * 未限定的一侧为 null；count 为区间内事件实际数量。
 */
public record IntegrityEventsResponse(long releaseId, String fromUtc, String toUtc, int count,
                                      List<TaskDiagnosticsResponse.EventView> events) {
}
