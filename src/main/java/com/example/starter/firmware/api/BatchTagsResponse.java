package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量标签修改结果。
 *
 * @param dictVersion    提交后标签字典版本（实际值）
 * @param requestedCount 本批要求修改的设备项数（要求值）
 * @param changedCount   标签实际发生变化的设备项数（实际值）
 * @param unchangedCount 与原标签集合一致、无变化的设备项数（差额 = requestedCount - changedCount）
 * @param items          逐项最终状态，按请求顺序返回
 */
public record BatchTagsResponse(long dictVersion, int requestedCount, int changedCount, int unchangedCount,
                                List<Item> items) {

    /**
     * 单项结果。
     *
     * @param deviceId 设备ID
     * @param changed  本次是否实际改变了标签集合
     * @param tags     规范化排序后的最终标签集合
     */
    public record Item(String deviceId, boolean changed, List<String> tags) {
    }
}
