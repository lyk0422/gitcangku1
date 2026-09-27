package com.example.starter.maintenance.domain;

/**
 * 模板必检项（随模板版本固化，不可变）。
 *
 * @param equipmentId      所属设备标识
 * @param templateVersion  所属模板版本号
 * @param ordinal          模板内规范序号，从 0 开始连续，结果按其升序排列
 * @param itemCode         检查项编码，模板版本内唯一
 * @param itemTitle        检查项名称
 */
public record TemplateItem(
        String equipmentId,
        int templateVersion,
        int ordinal,
        String itemCode,
        String itemTitle) {
}
