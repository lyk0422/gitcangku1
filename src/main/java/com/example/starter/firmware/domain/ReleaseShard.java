package com.example.starter.firmware.domain;

/**
 * 发布版本分片摘要清单条目。序号从0开始连续，登记后不可修改。
 *
 * @param id          登记记录ID
 * @param releaseId   所属发布单ID
 * @param shardNo     分片序号，从0开始
 * @param shardDigest 分片摘要，小写十六进制SHA-256（64字符）
 */
public record ReleaseShard(long id, long releaseId, int shardNo, String shardDigest) {
}
