package com.example.starter.firmware.api;

import java.util.List;

/**
 * 标签修订事件诊断查询响应（只读）。时间区间左闭右开 [fromUtc, toUtc)，未提供边界用 null 表示。
 */
public record LabelRevisionListResponse(String fromUtc, String toUtc, int count, List<LabelRevisionView> revisions) {
}
