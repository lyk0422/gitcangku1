package com.example.starter.firmware.api;

/**
 * 分片摘要视图，按分片序号升序返回。
 */
public record ShardView(int shardIndex, String shardDigest) {
}
