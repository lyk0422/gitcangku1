package com.example.starter.repo;

import java.util.List;

/**
 * 改航候选集评估不可变记录。
 *
 * <p>固化评估事务读取的空域版本、航线版本（替换前）、替换后版本、
 * 全部候选逐个结论与选中序号；记录一经写入不再修改。</p>
 *
 * @param evaluationKey   评估唯一标识（全局唯一）
 * @param routeId         目标航线标识
 * @param routeVersion    评估读取的航线版本（替换前）
 * @param newRouteVersion  选中替换后的航线版本
 * @param airspaceVersion 评估读取并固化的空域版本
 * @param selectedIndex   选中候选序号（从 1 开始）
 * @param candidates      全部候选的逐个结论
 * @param requestId       评估写操作请求标识（即 evaluationKey）
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record RerouteEvaluationPo(String evaluationKey, String routeId, int routeVersion,
                                  int newRouteVersion, long airspaceVersion, int selectedIndex,
                                  List<CandidateResultPo> candidates, String requestId,
                                  long createdAt) {
}
