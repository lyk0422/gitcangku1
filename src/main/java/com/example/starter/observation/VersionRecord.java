package com.example.starter.observation;

import java.time.Instant;

/**
 * 带全局版本号与提交时刻的历史版本记录：按时刻一致视图与快照回放的读取单元。
 *
 * @param snapshot       该版本完整内容（墓碑时业务字段为 null）
 * @param globalRevision 该版本对应的全局版本号
 * @param committedAtUtc 该版本提交完成时刻（UTC）
 */
public record VersionRecord(
        ObservationSnapshot snapshot,
        long globalRevision,
        Instant committedAtUtc) {
}
