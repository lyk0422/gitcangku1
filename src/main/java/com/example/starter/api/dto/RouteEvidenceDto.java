package com.example.starter.api.dto;

import java.util.List;

/**
 * 单条参与航线的冻结证据：前后序列、版本与激活时的审查依据。
 *
 * @param routeId                航线标识
 * @param fromVersion            转配前版本
 * @param toVersion              转配后版本
 * @param reviewId               审查依据（当前 CLEAR 审核记录标识）
 * @param reviewRouteVersion     审查依据的航线版本
 * @param reviewAirspaceVersion  审查依据的空域版本
 * @param beforePlan             转配前穿越序列
 * @param afterPlan              转配后穿越序列
 */
public record RouteEvidenceDto(
        String routeId,
        Integer fromVersion,
        Integer toVersion,
        String reviewId,
        Integer reviewRouteVersion,
        Long reviewAirspaceVersion,
        List<PlanSlotDto> beforePlan,
        List<PlanSlotDto> afterPlan) {
}
