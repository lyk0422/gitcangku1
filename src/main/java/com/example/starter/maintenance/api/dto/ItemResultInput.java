package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 证据包检查项结果提交项。
 *
 * @param itemCode 检查项编码，须属于设备当前模板版本
 * @param result   检查结果：PASS 或 FAIL
 * @param note     检查备注，可空
 */
public record ItemResultInput(
        @NotBlank String itemCode,
        @NotBlank String result,
        String note) {
}
