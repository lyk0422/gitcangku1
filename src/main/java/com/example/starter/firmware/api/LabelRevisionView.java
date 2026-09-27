package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.LabelRevision;

/**
 * 标签字典修订事件只读视图。revisedAtUtc 为修订提交时刻（UTC，ISO-8601）。
 */
public record LabelRevisionView(long revisionId, String eventType, String operator, int deviceCount,
                                int newTagCount, long dictVersion, String revisedAtUtc) {

    public static LabelRevisionView of(LabelRevision revision) {
        return new LabelRevisionView(revision.id(), revision.eventType(), revision.operator(),
                revision.deviceCount(), revision.newTagCount(), revision.dictVersion(), revision.revisedAtUtc());
    }
}
