package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 设备全部保养项目状态汇总：项目按 itemCode 字典序排序。
 *
 * @param equipmentId  设备唯一标识
 * @param version      设备当前版本号
 * @param items        全部项目状态（按 itemCode 升序）
 */
public record ItemStatusSummaryResponse(
        String equipmentId,
        long version,
        List<ItemStatusResponse> items) {
}
