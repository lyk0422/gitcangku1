package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 保养模板视图（当前版本）。
 *
 * @param equipmentId  设备标识（同模板标识）
 * @param version      模板版本号，从 1 开始
 * @param items        检查项（按 position 升序）
 */
public record TemplateResponse(
        String equipmentId,
        int version,
        List<TemplateItemView> items) {

    /**
     * 模板检查项视图。
     *
     * @param position  规范排序位置，从 1 开始
     * @param itemCode  检查项编码
     * @param itemName  检查项名称
     * @param required  是否必检项
     */
    public record TemplateItemView(
            int position,
            String itemCode,
            String itemName,
            boolean required) {
    }
}
