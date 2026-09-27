package com.example.starter.firmware.domain;

/**
 * 发布单登记的单个分片摘要。
 *
 * @param releaseId   所属发布单ID
 * @param shardIndex  分片序号，从0开始连续
 * @param shardDigest 分片摘要，64位小写十六进制SHA-256
 */
public record ReleaseShard(long releaseId, int shardIndex, String shardDigest) {
}
