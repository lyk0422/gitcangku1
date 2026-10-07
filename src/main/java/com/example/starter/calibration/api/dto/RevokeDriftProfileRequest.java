package com.example.starter.calibration.api.dto;

/**
 * 撤销参考漂移档案请求。写操作使用全局 requestId 幂等。
 *
 * @param requestId       全局请求 ID（幂等键），非空
 * @param operator        操作者，非空
 * @param expectedVersion 期望版本（可选，参与幂等身份指纹）
 */
public record RevokeDriftProfileRequest(
        String requestId,
        String operator,
        Long expectedVersion) {
}
