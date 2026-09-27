package com.example.starter.firmware.domain;

/**
 * 任务分片接收证据（持久化行），只增不改；重新拉取产生新代次时旧代次证据保留。
 *
 * @param id             接收证据ID
 * @param taskId         所属任务ID
 * @param attempt        接收尝试代次
 * @param seqNo          分片序号，从0开始
 * @param receivedDigest 设备提交的接收摘要，64位小写十六进制 SHA-256
 * @param expectedDigest 登记的期望分片摘要快照
 * @param releaseVersion 接收时刻发布单版本号快照
 * @param receivedAtUtc  接收时刻，UTC，ISO-8601
 * @param requestId      提交该分片的请求ID
 */
public record TaskFragmentReceipt(long id, long taskId, int attempt, int seqNo,
                                  String receivedDigest, String expectedDigest,
                                  int releaseVersion, String receivedAtUtc, String requestId) {
}
