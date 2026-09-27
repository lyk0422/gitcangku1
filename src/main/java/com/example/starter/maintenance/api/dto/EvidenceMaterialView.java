package com.example.starter.maintenance.api.dto;

/**
 * 证据包材料批次摘要视图。
 *
 * @param batchCode    材料批次编码
 * @param materialName 材料名称（提交时批次快照）
 * @param quantity     使用数量，未知为 null
 * @param unit         数量单位，未提供为 null
 * @param summary      材料批次摘要说明
 */
public record EvidenceMaterialView(String batchCode, String materialName,
                                   Long quantity, String unit, String summary) {
}
