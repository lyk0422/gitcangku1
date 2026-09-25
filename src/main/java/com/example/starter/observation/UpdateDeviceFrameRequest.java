package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改设备坐标基准版本请求：同一事务内重算该设备所有未人工裁决观测的统一坐标与簇归属。
 *
 * @param requestId    全局唯一请求标识（幂等去重键）
 * @param frameVersion 目标坐标基准版本标识（必须已登记）
 */
public record UpdateDeviceFrameRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String frameVersion) {
}
