package com.example.starter.race.api;

import java.util.List;

/**
 * 赛事当前活跃器材绑定清单响应（按器材序列号、参赛号排序）。
 *
 * @param raceId   赛事ID
 * @param bindings 活跃绑定清单；选手退赛、取消资格或完赛后其绑定不再出现
 */
public record EquipmentBindingsResponse(
        String raceId,
        List<EquipmentBindingResponse> bindings
) {
}
