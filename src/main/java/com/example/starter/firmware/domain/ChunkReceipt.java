package com.example.starter.firmware.domain;

/**
 * 分片接收证据，只增不改。
 *
 * @param id            接收记录ID
 * @param taskId        所属任务ID
 * @param attemptNo     接收时所属尝试代次
 * @param chunkIndex    分片序号
 * @param digest        设备提交的分片摘要
 * @param receivedAtUtc 接收时刻，UTC，ISO-8601格式
 */
public record ChunkReceipt(long id, long taskId, int attemptNo, int chunkIndex, String digest,
                           String receivedAtUtc) {
}
