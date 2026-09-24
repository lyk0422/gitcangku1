package com.example.starter.api.dto;

import java.util.List;

/**
 * 改航候选集评估结果（不可变）。历史查询永远返回保存时的原内容，
 * 不因后续禁飞区变更或航线点列替换被改写。
 *
 * @param evaluationId    评估记录唯一标识（不可变）
 * @param evaluationKey   客户端提交的评估幂等键
 * @param routeId         目标航线标识
 * @param routeVersion    评估时读取（替换前）的航线版本
 * @param newRouteVersion 选中候选替换后的航线版本
 * @param airspaceVersion 评估时读取并固化的空域版本
 * @param selectedIndex   第一个 CLEAR 候选的序号（从 0 开始）
 * @param selectedPoints  选中候选的有序点列（已写入航线）
 * @param candidates      全部候选的逐个结论（含命中 zoneId，字典序去重）
 */
public record EvaluationResultDto(
        String evaluationId,
        String evaluationKey,
        String routeId,
        Integer routeVersion,
        Integer newRouteVersion,
        Long airspaceVersion,
        Integer selectedIndex,
        List<RoutePointDto> selectedPoints,
        List<EvaluationCandidateDto> candidates) {
}
