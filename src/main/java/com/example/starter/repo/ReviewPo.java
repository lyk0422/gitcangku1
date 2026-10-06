package com.example.starter.repo;

import com.example.starter.domain.LinkGap;
import com.example.starter.domain.Point;
import com.example.starter.domain.SegmentUnion;

import java.util.List;

/**
 * 审核不可变结果记录。
 *
 * @param reviewId        审核记录唯一标识
 * @param routeId         航线标识
 * @param routeVersion    审核时的航线版本
 * @param airspaceVersion 审核时的空域版本
 * @param conclusion      CLEAR / BLOCKED / LINK_BLOCKED
 * @param hitZoneIds      命中 zoneId（字典序去重）
 * @param pointsSnapshot  审核时航点不可变快照
 * @param linkUnion       审核时各航段覆盖合并并集不可变快照（无通信档案为空列表）
 * @param linkGaps        审核时全部连续失联区间不可变快照（无失联或无档案为空列表）
 * @param requestId       提交审核的请求标识
 * @param createdAt       创建时间（epoch 毫秒）
 */
public record ReviewPo(String reviewId, String routeId, int routeVersion, long airspaceVersion,
                       String conclusion, List<String> hitZoneIds, List<Point> pointsSnapshot,
                       List<SegmentUnion> linkUnion, List<LinkGap> linkGaps,
                       String requestId, long createdAt) {
}
