package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 重复观测簇预览结果：只读冻结候选集合中每条记录的代次、设备、时间与字段值。
 * 预览不落库、不改变任何记录状态；候选资格（活跃、未归并、同站点同类型、时间范围）由提交集合决定。
 *
 * @param siteKey         全部候选记录共同的站点标识
 * @param observationType 全部候选记录共同的观测类型
 * @param observedAtFrom  候选记录观测时间范围下限（UTC）
 * @param observedAtTo    候选记录观测时间范围上限（UTC）
 * @param members         按记录键排序的成员冻结项
 */
public record ClusterPreviewResponse(
        String siteKey,
        String observationType,
        Instant observedAtFrom,
        Instant observedAtTo,
        List<ClusterMemberPreview> members) {
}
