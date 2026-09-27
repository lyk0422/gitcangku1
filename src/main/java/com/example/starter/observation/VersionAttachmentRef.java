package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测版本固化的附件证据引用：对应 observation_version_attachment 表的一行。
 * 观测版本生成时写入，历史永不更新；后续脱敏/撤销不追溯替换。
 *
 * @param observationId    观测记录标识
 * @param version          固化引用所属的观测版本号
 * @param attachmentId     被引用的附件标识
 * @param redactionVersion 固化引用的脱敏版本号；null 表示引用原始附件证据
 * @param createdAtUtc     引用固化时刻（UTC，等于观测版本生成时刻）
 */
public record VersionAttachmentRef(
        String observationId,
        int version,
        String attachmentId,
        Integer redactionVersion,
        Instant createdAtUtc) {
}
