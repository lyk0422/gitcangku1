package com.example.starter.race.api;

import com.example.starter.race.domain.RaceStatus;

import java.util.List;

/**
 * 赛事中止恢复事件历史（只读），按登记顺序稳定返回。
 *
 * @param raceId  赛事ID
 * @param version 当前赛事版本（只读查询不修改版本）
 * @param status  OPEN / SUSPENDED / SEALED
 * @param events  全部中止恢复事件，按登记时间与事件键排列
 */
public record SuspensionHistoryResponse(
        String raceId,
        int version,
        RaceStatus status,
        List<SuspensionEventResponse> events
) {
}
