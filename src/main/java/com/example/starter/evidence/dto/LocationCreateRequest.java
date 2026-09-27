package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 库位创建请求。locationCode 全局唯一，初始状态为 ACTIVE。
 *
 * @param commandKey   幂等命令键
 * @param locationCode 库位编码，全局唯一
 * @param locationName 库位名称
 */
public record LocationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String locationCode,
        @NotBlank @Size(max = 128) String locationName) {
}
