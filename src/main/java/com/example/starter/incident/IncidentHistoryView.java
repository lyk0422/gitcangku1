package com.example.starter.incident;

import java.util.List;

/**
 * 事件完整历史视图：当前状态 + 处置记录 + 历史流水。
 */
public record IncidentHistoryView(
        IncidentView incident,
        List<ActionView> actions,
        List<EventView> events) {
}
