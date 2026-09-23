package com.example.starter.repo;

/**
 * 飞行审核不可变持久化记录（响应快照以 JSON 保存）。
 *
 * @param reviewId        审核快照唯一标识
 * @param flightKey       飞行审核标识
 * @param finalized       是否为最终成功审核（CLEAR 为 true，BLOCKED 尝试为 false）
 * @param requestId       审核写操作的请求标识
 * @param routeId         被审核航线标识
 * @param routeVersion    冻结的航线版本
 * @param airspaceVersion 冻结的全局空域版本
 * @param permitId        CLEAR 核销使用的豁免包标识；未使用为 null
 * @param permitVersion   核销时豁免包版本；未使用为 null
 * @param reviewAt        审核指定时刻（epoch 毫秒，UTC）
 * @param conclusion      CLEAR / BLOCKED
 * @param responseJson    首次审核完整响应 JSON（重放原样返回）
 * @param createdAt       落库时间（epoch 毫秒，UTC）
 */
public record FlightReviewPo(String reviewId, String flightKey, boolean finalized,
                             String requestId, String routeId, int routeVersion,
                             long airspaceVersion, String permitId, Integer permitVersion,
                             long reviewAt, String conclusion, String responseJson,
                             long createdAt) {
}
