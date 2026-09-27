package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.TagRevision;

/**
 * 标签字典修订历史视图。时间为 UTC ISO-8601；requestedCount 为要求值，
 * changedCount 为实际变化值，unchangedCount 为无变化差额。
 */
public record TagRevisionView(long revision, String operator, int requestedCount, int changedCount,
                              int unchangedCount, String changedAtUtc) {

    public static TagRevisionView of(TagRevision revision) {
        return new TagRevisionView(revision.revision(), revision.operator(), revision.requestedCount(),
                revision.changedCount(), revision.requestedCount() - revision.changedCount(),
                revision.changedAtUtc());
    }
}
