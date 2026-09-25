package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 设备基准版本登记/变更请求：变更时在同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param frameVersion 新的设备坐标基准版本；未知版本返回 422
 */
public record UpdateDeviceFrameRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 32) String frameVersion) {
}
