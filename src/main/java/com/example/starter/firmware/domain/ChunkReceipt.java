package com.example.starter.firmware.domain;

/**
 * 分片接收证据，只增不改。
 *
 * @param id              接收记录ID
 * @param taskId          任务ID
 * @param attempt         接收时的尝试代次
 * @param releaseId       所属发布单ID快照
 * @param firmwareVersion 接收时固化的目标固件版本
 * @param chunkIndex      分片序号
 * @param digest          设备上报的接收摘要
 * @param receivedAtUtc   接收时刻，UTC，ISO-8601 毫秒精度
 */
public record ChunkReceipt(long id, long taskId, int attempt, long releaseId, String firmwareVersion,
                           int chunkIndex, String digest, String receivedAtUtc) {
}
