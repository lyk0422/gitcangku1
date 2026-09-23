package com.example.starter.calibration.api.dto;

/**
 * 创建失效单请求。
 *
 * @param invalidationKey 失效单业务键，全局唯一
 * @param rootVersionKey  失效根标准器版本业务键
 * @param effectiveFrom   失效起始时刻，ISO-8601（UTC，含该时刻）
 * @param expectedVersion 创建时客户端期望的领域版本号；与当前不一致返回 409
 * @param reason          失效原因
 */
public record CreateInvalidationRequest(
        String invalidationKey,
        String rootVersionKey,
        String effectiveFrom,
        Long expectedVersion,
        String reason) {
}
