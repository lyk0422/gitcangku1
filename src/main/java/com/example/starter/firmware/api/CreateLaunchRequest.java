package com.example.starter.firmware.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建画像投放请求：发布人选择固件版本，投放服务按设备当次冻结画像筛选目标。
 * 同一 requestId 重放首次目标清单；换版本（异参）返回 409。
 */
public record CreateLaunchRequest(
        @NotBlank @Size(max = 64) String requestId,
        long versionId) {
}
