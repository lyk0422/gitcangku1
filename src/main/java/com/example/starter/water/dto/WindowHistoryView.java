package com.example.starter.water.dto;

import java.util.List;

/**
 * 窗口历史明细视图：窗口本体、全部限供记录（含已取消）与全部配水申请。
 */
public record WindowHistoryView(
        WindowView window,
        List<RestrictionView> restrictions,
        List<AllocationView> allocations) {
}
