package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 维护窗口有效集诊断响应：当前有效窗口集合，以及给定业务时刻（左闭右开）命中的窗口。
 *
 * @param stationId        观测站标识
 * @param atUtc            诊断时刻（UTC）
 * @param effectiveCount   有效窗口数量（实际值）
 * @param effectiveWindows 有效窗口明细，按维护版本升序
 * @param matchedWindowId  诊断时刻命中的窗口标识（左闭右开，多命中取最高维护版本）；未命中为 null
 */
public record MaintenanceEffectiveAtResponse(
        String stationId,
        Instant atUtc,
        int effectiveCount,
        List<MaintenanceWindowResponse> effectiveWindows,
        String matchedWindowId) {
}
