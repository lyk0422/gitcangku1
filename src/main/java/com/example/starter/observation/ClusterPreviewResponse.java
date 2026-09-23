package com.example.starter.observation;

import java.time.Instant;
import java.util.List;

/**
 * 簇候选预览响应：只读快照列表，按 observedAt、recordKey 升序；不锁定、不落库、不后台聚类。
 *
 * @param siteKey       查询站点键
 * @param type          查询观测类型
 * @param anchor        时间锚点（UTC）
 * @param windowSeconds 生效时间窗半宽秒数
 * @param candidates    时间窗内活跃未归并候选（含锚点记录），按 observedAt、recordKey 升序
 */
public record ClusterPreviewResponse(
        String siteKey,
        String type,
        Instant anchor,
        int windowSeconds,
        List<ClusterCandidateView> candidates) {
}
