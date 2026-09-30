package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数修订历史条目。
 *
 * @param revisionNo         修订号，1 为初始登记值
 * @param cumulativeMinutes  该修订版本的累计工时（分钟）
 * @param requestId          产生该修订的请求 requestId
 * @param submitterId        修订提交人；初始登记行为 null
 * @param witnessId          见证该版本生效的另一维护人；阈值内直接生效的修订为 null
 * @param createdAt          该修订生效时刻（UTC）
 */
public record RevisionView(
        int revisionNo,
        long cumulativeMinutes,
        String requestId,
        String submitterId,
        String witnessId,
        Instant createdAt) {
}
