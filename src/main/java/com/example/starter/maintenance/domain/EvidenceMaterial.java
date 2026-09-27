package com.example.starter.maintenance.domain;

/**
 * 证据包材料批次摘要（不可变）。同一设备周期内批次不可重复。
 *
 * @param packageId    所属证据包标识
 * @param batchCode    材料批次编码，须已登记且未撤销
 * @param materialName 材料名称（提交时批次快照）
 * @param quantity     使用数量，正整数；null 表示未知
 * @param unit         数量单位（如 pcs/L）；null 表示未提供
 * @param summary      材料批次摘要说明
 */
public record EvidenceMaterial(long packageId, String batchCode, String materialName,
                               Long quantity, String unit, String summary) {
}
