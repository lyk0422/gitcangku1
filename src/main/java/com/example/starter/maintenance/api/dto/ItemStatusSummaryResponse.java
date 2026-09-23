package com.example.starter.maintenance.api.dto;

import java.util.List;

/**
 * 全项目保养状态汇总：项目按 itemCode 升序排列。
 *
 * @param equipmentId  设备唯一标识
 * @param version      设备当前版本号
 * @param items        各项目状态列表（按 itemCode 升序）
 */
public record ItemStatusSummaryResponse(
        String equipmentId,
        long version,
        List<ItemStatusView> items) {
}
