package com.example.starter.firmware.domain;

/**
 * 任务尝试代次。INTEGRITY_FAILED 后重新拉取建立新代次，旧代次记录保留不改。
 *
 * @param id             尝试记录ID
 * @param taskId         所属任务ID
 * @param attemptNo      尝试代次，从1开始
 * @param status         本尝试状态（取值同任务状态）
 * @param createdAtUtc   尝试建立时刻，UTC，ISO-8601格式
 * @param finalizedAtUtc 本尝试终结时刻，UTC，ISO-8601格式；未终结为 null
 */
public record TaskAttempt(long id, long taskId, int attemptNo, TaskStatus status,
                          String createdAtUtc, String finalizedAtUtc) {
}
