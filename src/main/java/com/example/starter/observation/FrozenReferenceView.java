package com.example.starter.observation;

import java.time.Instant;

/**
 * 观测版本固化的附件引用视图。
 *
 * @param observationId    观测记录标识
 * @param version          观测版本号
 * @param attachmentId     附件标识
 * @param redactionVersion 快照时引用的脱敏版本；null 表示引用原始摘要
 * @param frozenAtUtc      固化时刻（UTC，ISO-8601）
 */
public record FrozenReferenceView(
        String observationId,
        int version,
        String attachmentId,
        Integer redactionVersion,
        Instant frozenAtUtc) {

    public static FrozenReferenceView of(FrozenReference reference) {
        return new FrozenReferenceView(reference.observationId(), reference.version(),
                reference.attachmentId(), reference.redactionVersion(), reference.frozenAtUtc());
    }
}
