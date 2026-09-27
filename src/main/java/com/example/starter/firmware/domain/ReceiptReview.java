package com.example.starter.firmware.domain;

import java.time.Instant;

/**
 * 撤销链任务回执的人工复核单。
 *
 * @param id           复核记录ID
 * @param taskId       关联任务ID
 * @param chainId      任务冻结的被撤销证书链ID
 * @param status       PENDING_REVIEW / CONFIRMED / REJECTED
 * @param rejectReason 拒绝原因；确认或待复核时为 null
 * @param reviewedBy   复核操作者；待复核时为 null
 * @param reviewedAt   复核时刻（UTC）；待复核时为 null
 */
public record ReceiptReview(long id, long taskId, long chainId, ReviewStatus status,
                            String rejectReason, String reviewedBy, Instant reviewedAt) {
}
