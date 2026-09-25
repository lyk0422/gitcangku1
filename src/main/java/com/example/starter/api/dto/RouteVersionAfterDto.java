package com.example.starter.api.dto;

import java.util.List;

/**
 * 转配后航线版本与穿越序列。
 *
 * @param routeId     航线标识
 * @param fromVersion 转配前版本
 * @param toVersion   转配后版本（fromVersion + 1）
 * @param afterPlan   转配后穿越序列
 */
public record RouteVersionAfterDto(
        String routeId,
        Integer fromVersion,
        Integer toVersion,
        List<PlanSlotDto> afterPlan) {
}
