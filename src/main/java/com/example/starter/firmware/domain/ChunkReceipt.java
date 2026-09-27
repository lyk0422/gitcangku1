package com.example.starter.firmware.domain;

/**
 * 分片接收证据。同任务同代次同序号最多一笔，历史不可改。
 *
 * @param id             接收记录ID
 * @param taskId         任务ID
 * @param attemptNo      接收时任务所处尝试代次
 * @param chunkIndex     分片序号
 * @param digest         设备上报的分片摘要
 * @param expectedDigest 固化：接收时登记的分片摘要
 * @param matched        上报摘要与登记摘要是否一致
 * @param receivedAtUtc  接收时刻（UTC ISO-8601）
 */
public record ChunkReceipt(long id, long taskId, int attemptNo, int chunkIndex, String digest,
                           String expectedDigest, boolean matched, String receivedAtUtc) {
}
