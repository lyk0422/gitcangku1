package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 证据包材料批次摘要提交项。
 *
 * @param batchCode 材料批次编码，须已登记且未撤销；同一设备周期内不可重复
 * @param quantity  使用数量，正整数，可空（未知）
 * @param unit      数量单位（如 pcs/L），可空
 * @param summary   材料批次摘要说明
 */
public record MaterialSummaryInput(
        @NotBlank String batchCode,
        @Positive Long quantity,
        String unit,
        @NotBlank String summary) {
}
