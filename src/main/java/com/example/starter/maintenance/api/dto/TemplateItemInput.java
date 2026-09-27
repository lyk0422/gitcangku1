package com.example.starter.maintenance.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 模板检查项定义（创建/修订模板时使用）。
 *
 * @param itemCode   检查项编码，模板版本内唯一
 * @param itemName   检查项名称
 * @param required   是否必检项
 * @param sortOrder  模板内排序号（规范排序按 sortOrder、itemCode 升序）
 */
public record TemplateItemInput(
        @NotBlank String itemCode,
        @NotBlank String itemName,
        @NotNull Boolean required,
        @NotNull Integer sortOrder) {
}
