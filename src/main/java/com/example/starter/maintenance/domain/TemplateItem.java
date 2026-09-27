package com.example.starter.maintenance.domain;

/**
 * 保养模板检查项（按模板版本固化）。
 *
 * @param itemCode   检查项编码，模板版本内唯一
 * @param itemName   检查项名称
 * @param required   是否必检项：必检项在证据包中必须恰有一个 PASS 结果
 * @param sortOrder  模板内排序号，证据包规范排序按 (sortOrder, itemCode) 升序
 */
public record TemplateItem(String itemCode, String itemName, boolean required, int sortOrder) {
}
