package com.example.starter.maintenance.domain;

/**
 * 模板检查项版本行。模板修订追加新版本，旧版本行保留不改写。
 *
 * @param templateId  模板标识（等于设备标识）
 * @param version     模板版本号
 * @param position    规范排序位置，从 1 开始
 * @param itemCode    检查项编码，同版本内唯一
 * @param itemName    检查项名称
 * @param required    是否必检项；仅必检项纳入完工门禁
 */
public record MaintenanceTemplateItem(
        String templateId,
        int version,
        int position,
        String itemCode,
        String itemName,
        boolean required) {
}
