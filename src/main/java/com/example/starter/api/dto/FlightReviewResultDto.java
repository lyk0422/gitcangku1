package com.example.starter.api.dto;

import java.util.List;

/**
 * 航班豁免审核结果。
 *
 * @param reviewId        审核记录唯一标识；BLOCKED（未形成审核）时为 null
 * @param flightKey       航班业务标识
 * @param routeId         航线标识
 * @param routeVersion    一致视图中的航线版本
 * @param airspaceVersion 一致视图中的空域版本
 * @param permitKey       完成核销的豁免包标识；CLEAR 时有值，否则为 null
 * @param permitVersion   完成核销的豁免包版本；CLEAR 时有值，否则为 null
 * @param reviewAt        审核时刻，epoch 毫秒（UTC）
 * @param conclusion      CLEAR / BLOCKED
 * @param hitRegionKeys   命中的全部 regionKey（字典序去重）
 * @param deficits        BLOCKED 时每个命中区域的缺失/过期/耗尽缺口；CLEAR 为空
 * @param consumed        CLEAR 时每项扣减前后余额快照；否则为空
 * @param snapshot        审核快照：空域、航线、permit 版本、核销前后余额与几何命中
 */
public record FlightReviewResultDto(String reviewId, String flightKey, String routeId,
                                    int routeVersion, long airspaceVersion, String permitKey,
                                    Integer permitVersion, long reviewAt, String conclusion,
                                    List<String> hitRegionKeys, List<RegionDeficit> deficits,
                                    List<ConsumedQuota> consumed,
                                    FlightReviewSnapshot snapshot) {
}
