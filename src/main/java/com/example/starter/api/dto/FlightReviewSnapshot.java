package com.example.starter.api.dto;

import java.util.List;

/**
 * 审核提交时一致视图的不可变快照：空域版本、航线标识与版本、航点几何、
 * permit 标识与版本、全部几何命中区域，以及每个命中区域核销前后余额。
 *
 * @param airspaceVersion 提交时一致视图中的空域版本
 * @param routeId         航线标识
 * @param routeVersion    航线版本
 * @param pointsSnapshot  航线点列不可变快照
 * @param permitKey       完成核销的豁免包标识；BLOCKED 时为候选包标识或 null
 * @param permitVersion   豁免包版本；无候选包时为 null
 * @param hits            几何命中区域快照（含区域版本与命中判定）
 * @param consumed        每个命中区域核销前后余额；BLOCKED 未核销时为空
 */
public record FlightReviewSnapshot(long airspaceVersion, String routeId, int routeVersion,
                                   List<RoutePointDto> pointsSnapshot, String permitKey,
                                   Integer permitVersion, List<SnapshotHit> hits,
                                   List<ConsumedQuota> consumed) {
}
