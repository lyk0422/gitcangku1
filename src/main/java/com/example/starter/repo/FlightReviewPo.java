package com.example.starter.repo;

import java.util.List;

/**
 * 航班豁免审核不可变记录（仅 CLEAR 成功审核落库；BLOCKED 整体回滚不占用 flightKey）。
 *
 * @param reviewId        审核记录唯一标识
 * @param flightKey       航班业务标识，全局只能形成一次成功审核
 * @param routeId         被审核航线标识
 * @param routeVersion    提交时一致视图中的航线版本
 * @param airspaceVersion 提交时一致视图中的空域版本
 * @param reviewAt        客户端指定的审核时刻，epoch 毫秒（UTC）
 * @param conclusion      结论，落库记录恒为 CLEAR
 * @param hitRegionKeys   命中的全部 regionKey（字典序去重）
 * @param permitKey       完成全部核销的同一豁免包标识
 * @param snapshotJson    审核快照：空域/航线/permit 版本、核销前后余额与几何命中
 * @param requestId       审核写操作请求标识
 * @param requestHash     审核请求规范化参数哈希，用于同 flightKey 异参 409 判定
 * @param createdAt       创建时间，epoch 毫秒（UTC）
 */
public record FlightReviewPo(String reviewId, String flightKey, String routeId,
                             int routeVersion, long airspaceVersion, long reviewAt,
                             String conclusion, List<String> hitRegionKeys, String permitKey,
                             String snapshotJson, String requestId, String requestHash,
                             long createdAt) {
}
