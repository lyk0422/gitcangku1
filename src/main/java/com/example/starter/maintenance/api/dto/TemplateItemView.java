package com.example.starter.maintenance.api.dto;

/**
 * 模板检查项视图。
 *
 * @param itemCode   检查项编码
 * @param itemName   检查项名称
 * @param required   是否必检项
 * @param sortOrder  模板内排序号
 */
public record TemplateItemView(String itemCode, String itemName, boolean required, int sortOrder) {
}
