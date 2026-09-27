package com.example.starter.firmware.domain;

/**
 * 发布版本分片清单中的一个分片摘要。
 *
 * @param chunkIndex 分片序号，从0连续
 * @param digest     分片摘要，64位小写十六进制SHA-256
 */
public record ManifestChunk(int chunkIndex, String digest) {
}
