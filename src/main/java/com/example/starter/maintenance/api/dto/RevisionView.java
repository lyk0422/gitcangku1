package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数修订历史条目。
 *
 * @param revisionNo         修订号，1 为初始登记值
 * @param cumulativeMinutes  该修订版本的归一累计工时（分钟）
 * @param raw                该修订版本的原始读数 raw；null 表示未启用量程
 * @param wrapCount          该修订版本的回绕次数；null 表示未启用量程
 * @param requestId          产生该修订的请求 requestId
 * @param createdAt          该修订生效时刻（UTC）
 */
public record RevisionView(
        int revisionNo,
        long cumulativeMinutes,
        Long raw,
        Long wrapCount,
        String requestId,
        Instant createdAt) {
}
