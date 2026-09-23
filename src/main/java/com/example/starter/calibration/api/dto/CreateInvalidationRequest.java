package com.example.starter.calibration.api.dto;

/**
 * 创建失效单请求。requestId 为幂等键：同参重放返回首次闭包快照，异参 409，失败不占键。
 *
 * @param requestId       幂等请求键，全局唯一
 * @param invalidationKey 失效单业务键，全局唯一
 * @param rootStandardId  失效根标准器版本业务键
 * @param invalidFrom     失效起始时刻，ISO-8601（按 UTC 归一）
 * @param expectedVersion 创建时根标准器版本号；激活时不一致整单 409
 * @param reason          失效原因
 */
public record CreateInvalidationRequest(
        String requestId,
        String invalidationKey,
        String rootStandardId,
        String invalidFrom,
        Integer expectedVersion,
        String reason) {
}
