package com.example.starter.firmware.domain;

/**
 * 分片接收证据，只增不改。固化接收时刻的发布单版本、分片摘要与UTC时刻；
 * 发布单撤回或设备重拉（新代次）均不改写既有证据。
 *
 * @param id             证据ID
 * @param taskId         任务尝试ID
 * @param attemptNo      尝试代次
 * @param releaseId      发布单ID快照
 * @param releaseVersion 接收时刻发布单版本号快照
 * @param shardNo        分片序号，从0开始
 * @param shardDigest    设备实际提交的分片摘要，原样固化
 * @param receivedAtUtc  接收时刻，UTC，ISO-8601格式
 * @param requestId      提交该批分片的请求ID
 */
public record ShardReceipt(long id, long taskId, int attemptNo, long releaseId, int releaseVersion,
                           int shardNo, String shardDigest, String receivedAtUtc, String requestId) {
}
