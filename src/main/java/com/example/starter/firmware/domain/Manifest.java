package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 发布版本分片清单：规范排序的分片摘要与完整包聚合摘要。
 *
 * @param releaseId       所属发布单ID
 * @param firmwareVersion 目标固件版本快照，登记时固化
 * @param chunkCount      分片总数，>=1，序号为 0~chunkCount-1 连续
 * @param packageDigest   完整包聚合摘要，64位小写十六进制 SHA-256
 * @param registeredAtUtc 最近一次登记时刻，UTC，ISO-8601 毫秒精度
 * @param chunkDigests    按序号升序的分片摘要，下标即分片序号
 */
public record Manifest(long releaseId, String firmwareVersion, int chunkCount, String packageDigest,
                       String registeredAtUtc, List<String> chunkDigests) {
}
