package com.example.starter.maintenance.domain;

/**
 * 证据包检查项结果（不可变）。名称/必检/排序为提交时模板快照。
 *
 * @param packageId  所属证据包标识
 * @param itemCode   检查项编码（提交时必须属于设备当前模板版本）
 * @param itemName   检查项名称（提交时模板快照）
 * @param required   是否必检项（提交时模板快照）
 * @param sortOrder  规范排序号（提交时模板快照）
 * @param result     检查结果：PASS 或 FAIL；存在 FAIL 时不可完工
 * @param note       检查备注，可空（null 表示无备注）
 */
public record EvidenceItemResult(long packageId, String itemCode, String itemName, boolean required,
                                 int sortOrder, String result, String note) {
}
