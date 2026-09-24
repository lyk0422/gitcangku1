package com.example.starter.blind.dto;

import java.util.List;

/**
 * 扩容历史视图；实验关闭后记录仍保留可查。不含处理映射。
 *
 * @param experimentId 实验编号
 * @param extensions   按扩容先后顺序排列的扩容记录
 */
public record ExtensionHistoryView(
        String experimentId,
        List<ExtensionHistoryItem> extensions
) {
}
