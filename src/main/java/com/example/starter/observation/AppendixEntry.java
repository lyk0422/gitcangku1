package com.example.starter.observation;

import java.time.Instant;

/**
 * 更正附录链中的单条差异：记录字段旧值、新值以及操作者与时刻。
 *
 * @param appendixVersion          附录版本号，从 1 开始
 * @param previousAppendixVersion  前一附录版本号；V1 为 null
 * @param resolutionId             前置冲突裁决记录标识
 * @param snapshotVersion          原始快照版本号
 * @param operator                 提交更正的观测记录员标识
 * @param correctedAtUtc           更正完成时刻（UTC）
 * @param fieldName                更正字段名
 * @param oldValue                 字段旧值完整原文
 * @param newValue                 字段新值完整原文
 */
public record AppendixEntry(
        int appendixVersion,
        Integer previousAppendixVersion,
        String resolutionId,
        int snapshotVersion,
        String operator,
        Instant correctedAtUtc,
        String fieldName,
        String oldValue,
        String newValue) {

    /**
     * 由不可变附录记录构造链上差异条目。
     */
    public static AppendixEntry of(CorrigendumAppendix appendix) {
        return new AppendixEntry(
                appendix.appendixVersion(),
                appendix.previousAppendixVersion(),
                appendix.resolutionId(),
                appendix.snapshotVersion(),
                appendix.operator(),
                appendix.correctedAtUtc(),
                appendix.fieldName(),
                appendix.oldValue(),
                appendix.newValue());
    }
}
