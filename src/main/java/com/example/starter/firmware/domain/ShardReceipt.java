package com.example.starter.firmware.domain;

/**
 * 分片接收证据，只增不改：每次分片提交都固化设备原始摘要与比对结果。
 *
 * @param id             证据ID
 * @param taskId         所属任务ID
 * @param attemptNo      接收时尝试代次
 * @param releaseId      接收时发布单ID（快照）
 * @param releaseVersion 接收时发布单版本号（快照）
 * @param shardIndex     设备提交的分片序号，越界提交也原样固化
 * @param expectedDigest 登记的期望摘要；越界序号无期望值时为 null
 * @param receivedDigest 设备实际提交摘要，原样固化（含格式非法的不匹配提交）
 * @param digestMatched  摘要是否匹配
 * @param receivedAtUtc  接收时刻，UTC ISO-8601
 */
public record ShardReceipt(long id, long taskId, int attemptNo, long releaseId, int releaseVersion,
                           int shardIndex, String expectedDigest, String receivedDigest,
                           boolean digestMatched, String receivedAtUtc) {
}
