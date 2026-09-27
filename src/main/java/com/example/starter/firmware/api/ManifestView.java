package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ManifestChunk;
import com.example.starter.firmware.repo.ManifestRepository;

import java.util.List;

/**
 * 发布版本分片清单视图（明细查询，只读）。
 */
public record ManifestView(long releaseId, int chunkCount, String aggregateDigest,
                           String registeredAtUtc, List<ChunkItem> chunks) {

    /**
     * 分片摘要项视图。
     */
    public record ChunkItem(int index, String digest) {
    }

    public static ManifestView of(ManifestRepository.ManifestRow manifest, List<ManifestChunk> chunks) {
        return new ManifestView(manifest.releaseId(), manifest.chunkCount(), manifest.aggregateDigest(),
                manifest.registeredAtUtc(),
                chunks.stream().map(c -> new ChunkItem(c.chunkIndex(), c.digest())).toList());
    }
}
