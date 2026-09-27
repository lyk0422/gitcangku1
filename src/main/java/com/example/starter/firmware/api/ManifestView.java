package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单分片清单视图（只读诊断）：locked=true 表示已有任务拉取，清单永久冻结。
 */
public record ManifestView(String packageDigest, boolean locked, String lockedAtUtc,
                          int requiredShards, List<ShardView> shards) {
}
