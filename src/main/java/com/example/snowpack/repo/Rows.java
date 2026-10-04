package com.example.snowpack.repo;

import java.time.Instant;

/** 数据库行记录类型。时间字段均为 UTC。 */
public final class Rows {

    private Rows() {
    }

    /** 控制区状态行。 */
    public record ZoneStateRow(String zoneId, String status, long readingVersion, Instant updatedAt) {
    }

    /** 雪况读数行。 */
    public record ReadingRow(long id, String zoneId, Instant hourUtc, int snowDepthCm, int newSnowCm,
                             String requestId, Instant createdAt) {
    }

    /** 勘误行。 */
    public record ErrataRow(long id, long readingId, String requestId, String note,
                            String reviewResult, Instant createdAt) {
    }

    /** 封闭命令行。 */
    public record ClosureRow(long id, String zoneId, String requestId, String source, String reason,
                             String status, Instant createdAt, Instant liftedAt) {
    }

    /** 解除审核单行。 */
    public record ReviewRow(long id, String zoneId, long closureId, String status,
                            String firstReviewer, String secondReviewer,
                            String firstRequestId, String secondRequestId,
                            long readingVersion, long windowHeadReadingId,
                            String invalidateReason, Instant createdAt, Instant completedAt) {
    }

    /** 解除决定版本行。 */
    public record DecisionRow(long id, long reviewId, String zoneId, int versionNo, Instant createdAt) {
    }
}