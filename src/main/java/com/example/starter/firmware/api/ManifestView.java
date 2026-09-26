package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.Manifest;

import java.util.List;
import java.util.stream.IntStream;

/**
 * 发布版本分片清单视图。locked 为 true 表示已有任务拉取、清单不可再修改。
 */
public record ManifestView(long releaseId, String firmwareVersion, int chunkCount,
                           String packageDigest, boolean locked, String registeredAtUtc,
                           List<ManifestChunkView> chunks) {

    /**
     * 清单分片明细，index 即分片序号。
     */
    public record ManifestChunkView(int index, String digest) {
    }

    public static ManifestView of(Manifest manifest, boolean locked) {
        List<ManifestChunkView> chunks = IntStream.range(0, manifest.chunkDigests().size())
                .mapToObj(i -> new ManifestChunkView(i, manifest.chunkDigests().get(i)))
                .toList();
        return new ManifestView(manifest.releaseId(), manifest.firmwareVersion(), manifest.chunkCount(),
                manifest.packageDigest(), locked, manifest.registeredAtUtc(), chunks);
    }
}
