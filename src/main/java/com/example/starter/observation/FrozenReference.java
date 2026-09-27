package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测版本固化的附件引用：对应 observation_version_attachment 表的一行。
 * 合并快照与人工冲突选择生成观测版本时写入，后续脱敏不追溯改写。
 *
 * @param observationId    观测记录标识
 * @param version          观测版本号
 * @param attachmentId     附件标识
 * @param redactionVersion 快照时引用的脱敏版本；null 表示引用原始摘要
 * @param frozenAtUtc      固化时刻（UTC）
 */
public record FrozenReference(
        String observationId,
        int version,
        String attachmentId,
        Integer redactionVersion,
        Instant frozenAtUtc) {
}
