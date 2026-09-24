package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单顺延汇总视图（只读）：按设备ID稳定排序的顺延明细及合计。
 */
public record ReleaseDeferralSummaryView(long releaseId, int deferredDeviceCount, int totalDeferCount,
                                         List<DeferralRecordView> records) {
}
