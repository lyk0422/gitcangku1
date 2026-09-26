package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ReleaseShard;

import java.util.List;

/**
 * 发布版本分片清单视图：规范排序（序号升序）的分片摘要与完整包聚合摘要。
 */
public record ShardManifestView(long releaseId, int shardCount, String fullDigest,
                                List<ShardDigestView> shards) {

    /**
     * 单个分片摘要视图。
     */
    public record ShardDigestView(int shardNo, String digest) {
    }

    public static ShardManifestView of(long releaseId, String fullDigest, List<ReleaseShard> shards) {
        return new ShardManifestView(releaseId, shards.size(), fullDigest,
                shards.stream().map(s -> new ShardDigestView(s.shardNo(), s.shardDigest())).toList());
    }
}
