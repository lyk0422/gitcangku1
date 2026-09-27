package com.example.starter.maintenance;

import java.time.Instant;
import java.util.List;

/**
 * 维护窗口诊断响应：汇总站点窗口数量与封存状态，便于调用方直接读取实际值。
 *
 * @param stationId         观测站唯一标识
 * @param activatedAtUtc    站点启用时刻（UTC）
 * @param totalWindows      窗口总数（含各状态）
 * @param activeWindows     生效中窗口数
 * @param supersededWindows 已被更正取代窗口数
 * @param withdrawnWindows  已撤回窗口数
 * @param sealedWindows     已被合并快照覆盖的窗口数
 * @param windows           全部窗口明细（按开始时刻、窗口标识稳定排序）
 */
public record WindowDiagnosticsResponse(
        String stationId,
        Instant activatedAtUtc,
        int totalWindows,
        int activeWindows,
        int supersededWindows,
        int withdrawnWindows,
        int sealedWindows,
        List<WindowResponse> windows) {
}
