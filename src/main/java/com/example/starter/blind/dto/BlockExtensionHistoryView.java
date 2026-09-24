package com.example.starter.blind.dto;

import java.util.List;

/**
 * 扩容历史视图；实验关闭后记录仍保留可查。不含处理映射与席位序号。
 *
 * @param experimentId 实验编号
 * @param version      当前实验版本
 * @param extensions   按提交时间、区组号升序排列的扩容记录
 */
public record BlockExtensionHistoryView(
        String experimentId,
        int version,
        List<BlockExtensionView> extensions
) {
}
