package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 失效单响应。创建与同参重放返回首次闭包快照；预览返回实时重算闭包。
 *
 * @param invalidationKey 失效单业务键
 * @param requestId       幂等请求键
 * @param rootStandardId  失效根标准器版本业务键
 * @param invalidFrom     失效起始时刻（UTC）
 * @param expectedVersion 创建时根标准器版本号
 * @param reason          失效原因
 * @param createdBy       创建人（质量负责人）
 * @param status          状态：PENDING_CONFIRMATION / ACTIVATED
 * @param impactVersion   激活后生成的影响版本号；未激活为 null 不输出
 * @param confirmations   已确认人列表（升序）
 * @param closure         影响闭包（稳定排序）
 * @param createdAt       创建时间（UTC）
 * @param activatedAt     激活时间（UTC）；未激活为 null 不输出
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record InvalidationResponse(
        String invalidationKey,
        String requestId,
        String rootStandardId,
        Instant invalidFrom,
        int expectedVersion,
        String reason,
        String createdBy,
        String status,
        String impactVersion,
        List<String> confirmations,
        InvalidationClosureDto closure,
        Instant createdAt,
        Instant activatedAt) {
}
