package com.example.starter.maintenance.api.dto;

/**
 * 证据包检查项结果视图（提交时模板快照）。
 *
 * @param itemCode   检查项编码
 * @param itemName   检查项名称（快照）
 * @param required   是否必检项（快照）
 * @param sortOrder  规范排序号（快照）
 * @param result     检查结果：PASS 或 FAIL
 * @param note       检查备注，无备注为 null
 */
public record EvidenceItemView(String itemCode, String itemName, boolean required,
                               int sortOrder, String result, String note) {
}
