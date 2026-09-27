package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ChunkEntry;

import java.util.List;

/**
 * 分片清单视图：发布单登记的规范排序分片摘要与完整包摘要（只读）。
 */
public record ManifestView(long releaseId, int chunkCount, String packageDigest,
                           List<ChunkEntry> chunks) {
}
