package com.example.starter.maintenance.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 保养模板版本视图。
 *
 * @param equipmentId               所属设备标识
 * @param templateVersion           模板版本号
 * @param overdueThresholdMinutes   OVERDUE 阈值（分钟）
 * @param items                     必检项（按 ordinal 升序的规范排序）
 * @param createdAt                 模板版本创建时刻（UTC）
 */
public record TemplateResponse(
        String equipmentId,
        int templateVersion,
        long overdueThresholdMinutes,
        List<TemplateItemView> items,
        Instant createdAt) {

    /**
     * 模板必检项视图。
     *
     * @param ordinal    模板内规范序号
     * @param itemCode   检查项编码
     * @param itemTitle  检查项名称
     */
    public record TemplateItemView(int ordinal, String itemCode, String itemTitle) {
    }
}
