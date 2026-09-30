package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数修订历史条目。
 *
 * @param revisionNo         修订号，1 为初始登记值
 * @param cumulativeMinutes  该修订版本生效时的累计工时（分钟）
 * @param requestId          产生该修订的请求 requestId
 * @param thresholdMinutes   大幅修订见证阈值快照（分钟）；初始登记与阈值内修订为 null
 * @param differenceMinutes  相对修订前有效读数的差额（分钟）；初始登记与阈值内修订为 null
 * @param submittedBy        提交修订的维护人标识；初始登记行为 null
 * @param witnessedBy        见证该大幅修订的另一维护人标识；初始登记与阈值内修订为 null
 * @param createdAt          该修订生效时刻（UTC，见证修订为见证完成时刻）
 */
public record RevisionView(
        int revisionNo,
        long cumulativeMinutes,
        String requestId,
        Long thresholdMinutes,
        Long differenceMinutes,
        String submittedBy,
        String witnessedBy,
        Instant createdAt) {
}
