package com.example.starter.firmware.domain;

/**
 * 分片摘要清单条目。
 *
 * @param chunkIndex 分片序号，从0开始连续
 * @param digest     分片摘要，SHA-256，64位小写十六进制
 */
public record ChunkEntry(int chunkIndex, String digest) {
}
