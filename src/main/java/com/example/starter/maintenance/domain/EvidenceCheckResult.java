package com.example.starter.maintenance.domain;

/**
 * 证据包检查项结果（不可变）。按 {@link #position()} 规范排序。
 *
 * @param equipmentId     所属设备标识
 * @param cycleNo         所属周期号
 * @param packageVersion  所属证据包版本号
 * @param position        规范排序位置，取自提交时模板的 position
 * @param itemCode        检查项编码
 * @param pass            是否 PASS；false 为 FAIL
 * @param note            检查备注，可空，null 表示无备注
 */
public record EvidenceCheckResult(
        String equipmentId,
        int cycleNo,
        int packageVersion,
        int position,
        String itemCode,
        boolean pass,
        String note) {
}
