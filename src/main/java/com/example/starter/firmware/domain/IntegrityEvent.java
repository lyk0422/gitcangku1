package com.example.starter.firmware.domain;

/**
 * 分片完整性事件，固化判定时的业务证据，只增不改。
 *
 * @param id              事件ID
 * @param taskId          所属任务ID
 * @param attemptNo       事件所属尝试代次
 * @param releaseId       所属发布单ID
 * @param eventType       事件类型
 * @param chunkIndex      相关分片序号；与单分片无关的事件为 null
 * @param expectedDigest  清单登记的期望摘要；无对应分片时为 null
 * @param receivedDigest  设备实际提交的摘要；非分片事件为 null
 * @param chunkCount      判定时分片总数；非判定事件为 null
 * @param aggregateDigest 判定时计算的聚合摘要；非判定事件为 null
 * @param packageDigest   判定时发布单登记的完整包摘要；非判定事件为 null
 * @param reason          失败原因；非失败事件为 null
 * @param detail          补充说明（如缺失序号列表）；无补充为 null
 * @param occurredAtUtc   事件发生时刻，UTC，ISO-8601格式
 */
public record IntegrityEvent(long id, long taskId, int attemptNo, long releaseId,
                             IntegrityEventType eventType, Integer chunkIndex,
                             String expectedDigest, String receivedDigest,
                             Integer chunkCount, String aggregateDigest, String packageDigest,
                             IntegrityFailureReason reason, String detail, String occurredAtUtc) {
}
