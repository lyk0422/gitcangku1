package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ChunkDigestInput;
import com.example.starter.firmware.domain.ChunkEntry;
import com.example.starter.firmware.error.ApiException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 分片清单登记校验：序号从0连续、摘要格式固定、聚合摘要必须匹配。
 * 校验失败返回 422，响应中给出实际值与要求值，不让调用方猜测。
 */
public final class Manifests {

    private Manifests() {
    }

    /**
     * 校验并规范化清单。两者都为 null 表示不登记清单（返回 null）；
     * 只提供其一时返回 400。校验通过返回按序号升序的清单条目。
     */
    public static List<ChunkEntry> validate(List<ChunkDigestInput> chunks, String packageDigest) {
        if (chunks == null && packageDigest == null) {
            return null;
        }
        if (chunks == null || packageDigest == null) {
            throw ApiException.badRequest("INVALID_MANIFEST", "chunks 与 packageDigest 必须同时提供或同时省略");
        }
        if (chunks.isEmpty()) {
            throw ApiException.unprocessable("MANIFEST_EMPTY", "分片清单不能为空：要求至少1个分片，实际0个");
        }
        if (!Digests.isSha256Hex(packageDigest)) {
            throw ApiException.unprocessable("INVALID_DIGEST_FORMAT",
                    "packageDigest 摘要格式不合法：要求64位小写十六进制，实际: " + packageDigest);
        }
        Set<Integer> seen = new HashSet<>();
        for (ChunkDigestInput chunk : chunks) {
            if (chunk.chunkIndex() == null || chunk.digest() == null) {
                throw ApiException.badRequest("INVALID_MANIFEST", "分片条目缺少 chunkIndex 或 digest");
            }
            if (chunk.chunkIndex() < 0) {
                throw ApiException.unprocessable("CHUNK_INDEX_GAP",
                        "分片序号必须从0开始连续：实际出现负数序号 " + chunk.chunkIndex());
            }
            if (!seen.add(chunk.chunkIndex())) {
                throw ApiException.unprocessable("DUPLICATE_CHUNK_INDEX",
                        "分片序号重复: " + chunk.chunkIndex());
            }
            if (!Digests.isSha256Hex(chunk.digest())) {
                throw ApiException.unprocessable("INVALID_DIGEST_FORMAT",
                        "分片 " + chunk.chunkIndex() + " 摘要格式不合法：要求64位小写十六进制，实际: "
                                + chunk.digest());
            }
        }
        List<ChunkEntry> sorted = chunks.stream()
                .map(c -> new ChunkEntry(c.chunkIndex(), c.digest()))
                .sorted(Comparator.comparingInt(ChunkEntry::chunkIndex))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            if (sorted.get(i).chunkIndex() != i) {
                throw ApiException.unprocessable("CHUNK_INDEX_GAP",
                        "分片序号必须从0开始连续：要求序号 0.." + (sorted.size() - 1) + "，实际在序号 " + i
                                + " 处出现缺口（实际序号 " + sorted.get(i).chunkIndex() + "）");
            }
        }
        String aggregate = Digests.aggregateOf(sorted.stream().map(ChunkEntry::digest).toList());
        if (!aggregate.equals(packageDigest)) {
            throw ApiException.unprocessable("AGGREGATE_MISMATCH",
                    "聚合摘要不匹配：要求等于各分片摘要按序号拼接的SHA-256，实际计算值 " + aggregate
                            + "，登记值 " + packageDigest);
        }
        return new ArrayList<>(sorted);
    }

    /**
     * 清单的规范指纹（用于幂等异参检测），与顺序无关。
     */
    public static String fingerprintOf(List<ChunkEntry> sortedEntries, String packageDigest) {
        StringBuilder sb = new StringBuilder(packageDigest);
        for (ChunkEntry entry : sortedEntries) {
            sb.append('|').append(entry.chunkIndex()).append(':').append(entry.digest());
        }
        return Digests.sha256Hex(sb.toString());
    }
}
