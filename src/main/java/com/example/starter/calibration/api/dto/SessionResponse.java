package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 迟滞校准会话响应。version 等于已追加条目数；CLOSED 后全部方向与精确结果冻结。
 *
 * @param id            会话 ID
 * @param sessionKey    业务会话键
 * @param instrumentId  仪器 ID
 * @param certificateId 固定的 HYSTERESIS 证书 ID
 * @param lowerLimit    合格下限（十进制字符串，含端点）
 * @param upperLimit    合格上限（十进制字符串，含端点）
 * @param status        状态：OPEN / CLOSED
 * @param version       当前版本（已追加条目数）
 * @param createdBy     会话创建人
 * @param createdAt     创建时间（UTC）
 * @param closedAt      关闭时间（UTC），未关闭为 null
 * @param entries       已追加条目（按序号升序）
 */
public record SessionResponse(
        long id,
        String sessionKey,
        String instrumentId,
        long certificateId,
        String lowerLimit,
        String upperLimit,
        String status,
        int version,
        String createdBy,
        Instant createdAt,
        Instant closedAt,
        List<SessionEntryResponse> entries) {
}
