package com.example.starter.api.dto;

import java.util.List;

/**
 * 单条航线的预览：当前版本、转配前后穿越序列与该航线相关违规。
 *
 * @param routeId       航线标识
 * @param routeVersion  当前航线版本
 * @param beforePlan    转配前穿越序列
 * @param afterPlan     转配后穿越序列（按完整后态计算）
 * @param violations    该航线相关违规明细
 */
public record RoutePreviewDto(
        String routeId,
        Integer routeVersion,
        List<PlanSlotDto> beforePlan,
        List<PlanSlotDto> afterPlan,
        List<ViolationDto> violations) {
}
