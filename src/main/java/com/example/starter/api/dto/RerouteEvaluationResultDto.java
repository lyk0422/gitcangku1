package com.example.starter.api.dto;

import java.util.List;

/**
 * 改航候选集评估结果（成功选中首个 CLEAR 候选时返回，亦用于历史查询）。
 *
 * <p>评估记录不可变：固化读取的空域版本、航线版本、全部候选逐个结论与选中序号，
 * 后续禁飞区变更或航线点列替换都不会改写历史查询结果。</p>
 *
 * @param evaluationKey   评估唯一标识
 * @param routeId         目标航线标识
 * @param routeVersion    评估读取的航线版本（替换前版本）
 * @param newRouteVersion  选中替换后的航线版本（= routeVersion + 1）
 * @param airspaceVersion 评估读取并固化的空域版本
 * @param selectedIndex   选中候选序号（从 1 开始，按声明顺序首个 CLEAR）
 * @param candidates      全部候选的逐个结论（含点列与命中 zoneId）
 */
public record RerouteEvaluationResultDto(
        String evaluationKey,
        String routeId,
        Integer routeVersion,
        Integer newRouteVersion,
        Long airspaceVersion,
        Integer selectedIndex,
        List<CandidateResultDto> candidates) {
}
