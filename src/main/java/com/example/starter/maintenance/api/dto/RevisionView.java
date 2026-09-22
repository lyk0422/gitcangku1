package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数修订历史条目。
 *
 * @param revisionNo         修订号，1 为初始登记值
 * @param cumulativeMinutes  该修订版本的累计工时（分钟）
 * @param requestId          产生该修订的请求 requestId
 * @param createdAt          该修订生效时刻（UTC）
 */
public record RevisionView(
        int revisionNo,
        long cumulativeMinutes,
        String requestId,
        Instant createdAt) {
}
