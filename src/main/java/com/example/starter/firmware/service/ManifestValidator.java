package com.example.starter.firmware.service;

import com.example.starter.firmware.api.ShardInput;
import com.example.starter.firmware.domain.DigestRules;
import com.example.starter.firmware.error.ApiException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分片清单登记校验（纯逻辑，登记发布单与替换清单共用）：
 * packageDigest 与 shards 必须同时为空（遗留发布单）或同时非空；
 * 分片序号从0连续、不可重复；摘要必须为64位小写十六进制SHA-256；
 * 聚合摘要（有序分片摘要拼接后SHA-256）必须等于完整包摘要。任一不满足返回422，原因码可区分。
 */
@Component
public class ManifestValidator {

    /**
     * 校验并规范化（按序号升序）。返回 null 表示遗留发布单（无分片清单）。
     */
    public List<ShardInput> validate(String packageDigest, List<ShardInput> shards) {
        boolean hasPackage = packageDigest != null && !packageDigest.isBlank();
        boolean hasShards = shards != null && !shards.isEmpty();
        if (!hasPackage && !hasShards) {
            return null;
        }
        if (!hasPackage || !hasShards) {
            throw ApiException.unprocessable("MANIFEST_INCOMPLETE",
                    "完整包摘要与分片清单必须同时提供");
        }
        if (!DigestRules.isSha256Hex(packageDigest)) {
            throw ApiException.unprocessable("PACKAGE_DIGEST_INVALID",
                    "完整包摘要必须为64位小写十六进制SHA-256");
        }
        List<ShardInput> sorted = new ArrayList<>(shards);
        sorted.sort((a, b) -> Integer.compare(a.shardIndex(), b.shardIndex()));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).shardIndex().equals(sorted.get(i - 1).shardIndex())) {
                throw ApiException.unprocessable("SHARD_INDEX_DUPLICATE",
                        "分片序号重复: " + sorted.get(i).shardIndex());
            }
        }
        List<String> digests = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            ShardInput shard = sorted.get(i);
            if (shard.shardIndex() == null) {
                throw ApiException.unprocessable("SHARD_INDEX_REQUIRED", "分片序号不能为空");
            }
            if (shard.shardIndex() != i) {
                throw ApiException.unprocessable("SHARD_INDEX_GAP",
                        "分片序号必须从0连续，要求序号 " + i + "，实际 " + shard.shardIndex());
            }
            if (!DigestRules.isSha256Hex(shard.shardDigest())) {
                throw ApiException.unprocessable("SHARD_DIGEST_INVALID",
                        "分片 " + i + " 摘要必须为64位小写十六进制SHA-256");
            }
            digests.add(shard.shardDigest());
        }
        String aggregate = DigestRules.aggregateOfOrderedDigests(digests);
        if (!aggregate.equals(packageDigest)) {
            throw ApiException.unprocessable("AGGREGATE_DIGEST_MISMATCH",
                    "聚合摘要与完整包摘要不匹配");
        }
        return sorted;
    }
}
