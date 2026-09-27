package com.example.starter.firmware.domain;

/**
 * 任务完整性事件流水（持久化行），只增不改。
 *
 * @param id               事件ID
 * @param taskId           所属任务ID
 * @param attempt          接收尝试代次
 * @param eventType        RECEIVED 逐片接收；INTEGRITY_FAILED 完整性判定失败；INSTALLABLE 判定通过
 * @param releaseId        事件时刻所属发布单ID快照
 * @param releaseVersion   事件时刻发布单版本号快照
 * @param seqNo            RECEIVED 事件的分片序号；判定事件为 null
 * @param expectedDigest   RECEIVED 事件的期望分片摘要快照；判定事件为 null
 * @param receivedDigest   RECEIVED 事件设备实际提交摘要；判定事件为 null
 * @param aggregateExpected 判定时刻登记的完整包摘要；RECEIVED 事件为 null
 * @param aggregateActual  判定时刻按已接收分片聚合出的实际摘要；RECEIVED 事件为 null
 * @param reason           INTEGRITY_FAILED 原因代码；其余事件为 null
 * @param createdAtUtc     事件时刻，UTC，ISO-8601
 */
public record IntegrityEvent(long id, long taskId, int attempt, String eventType,
                             long releaseId, int releaseVersion, Integer seqNo,
                             String expectedDigest, String receivedDigest,
                             String aggregateExpected, String aggregateActual,
                             String reason, String createdAtUtc) {

    public static final String RECEIVED = "RECEIVED";
    public static final String INTEGRITY_FAILED = "INTEGRITY_FAILED";
    public static final String INSTALLABLE = "INSTALLABLE";
}
