package com.example.starter.permit;

/**
 * 许可观测复核状态常量，对应 permit_observation.review_status。
 */
public final class PermitReviewStatuses {

    /** 已接收、尚未经离线合并。 */
    public static final String RECEIVED = "RECEIVED";
    /** 已通过批量离线合并（若归属多观测冲突簇则仍待人工裁决）。 */
    public static final String MERGED = "MERGED";
    /** 许可撤销时被快照隔离、等待人工复核。 */
    public static final String PERMIT_REVIEW = "PERMIT_REVIEW";

    private PermitReviewStatuses() {
    }
}
