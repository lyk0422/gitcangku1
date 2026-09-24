package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单顺延汇总视图（只读）：被顺延任务总数与全部顺延次数合计，明细按设备ID稳定排序。
 */
public record DeferSummaryView(long releaseId, int deferredDevices, int totalDeferCount,
                               List<DeferStateView> defers) {
}
