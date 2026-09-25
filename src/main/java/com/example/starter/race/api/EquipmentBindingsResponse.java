package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事器材当前绑定清单。
 *
 * @param raceId  赛事ID
 * @param entries 绑定条目，按器材序列号字典序
 */
public record EquipmentBindingsResponse(
        String raceId,
        List<EquipmentBindingEntryResponse> entries
) {
}
