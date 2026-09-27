package com.example.starter.evidence.dto;

import java.util.List;

/**
 * 披露包内容详情：冻结摘要 + 逐件冻结快照（含逐件链诊断）。
 *
 * @param pkg   包冻结摘要
 * @param items 规范化排序的逐件冻结快照
 */
public record PackageDetailView(
        PackageView pkg,
        List<FrozenItemView> items) {
}
