package com.example.starter.repo;

import com.example.starter.domain.Point;

import java.util.List;

/**
 * 改航候选评估不可变记录（不含逐候选明细，明细见 {@link EvaluationCandidatePo}）。
 *
 * @param evaluationId    评估记录唯一标识
 * @param evaluationKey   客户端提交的评估幂等键
 * @param routeId         目标航线标识
 * @param routeVersion    评估时读取（替换前）的航线版本
 * @param newRouteVersion 选中候选替换后的航线版本
 * @param airspaceVersion 评估时读取并固化的空域版本
 * @param selectedIndex   选中候选序号（从 0 开始）
 * @param selectedPoints  选中候选点列不可变快照
 * @param createdAt       创建时间（epoch 毫秒，UTC）
 */
public record EvaluationPo(String evaluationId, String evaluationKey, String routeId,
                           int routeVersion, int newRouteVersion, long airspaceVersion,
                           int selectedIndex, List<Point> selectedPoints, long createdAt) {
}
