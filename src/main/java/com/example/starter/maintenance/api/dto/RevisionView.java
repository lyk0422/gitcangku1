package com.example.starter.maintenance.api.dto;

import java.time.Instant;

/**
 * 读数修订历史条目。
 *
 * @param revisionNo         修订号，1 为初始登记值
 * @param cumulativeMinutes  该修订版本的归一累计工时（分钟）
 * @param requestId          产生该修订的请求 requestId
 * @param createdAt          该修订生效时刻（UTC）
 * @param raw                该修订版本的量程内原始读数（分钟）；未启用量程设备为 null
 * @param wrapCount          该修订版本的回绕次数；未启用量程设备为 null
 */
public record RevisionView(
        int revisionNo,
        long cumulativeMinutes,
        String requestId,
        Instant createdAt,
        Long raw,
        Long wrapCount) {
}
