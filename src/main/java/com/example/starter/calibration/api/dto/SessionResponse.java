package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 校准会话响应。version 等于已提交条目数。
 *
 * @param id            会话 ID
 * @param instrumentId  仪器 ID
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 * @param status        状态：OPEN / CLOSED / RELEASED
 * @param version       当前版本=已提交条目数
 * @param createdAt     创建时间（UTC）
 * @param closedAt      关闭时间（UTC），未关闭为 null
 * @param entries       已追加条目（按序号升序）
 */
public record SessionResponse(
        long id,
        String instrumentId,
        long certificateId,
        String status,
        int version,
        Instant createdAt,
        Instant closedAt,
        List<SessionEntryResponse> entries) {
}
