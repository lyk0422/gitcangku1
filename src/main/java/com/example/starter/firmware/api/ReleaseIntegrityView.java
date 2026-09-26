package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单完整性诊断视图：分片清单（未登记时 shards 为空、fullDigest 为 null）
 * 与全部完整性判定事件。只读，查询不改变状态。
 */
public record ReleaseIntegrityView(long releaseId, Integer shardCount, String fullDigest,
                                   List<ShardManifestView.ShardDigestView> shards,
                                   List<IntegrityEventView> events) {
}
