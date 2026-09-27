package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 库位创建请求。初始状态 ACTIVE、库存版本 0。
 *
 * @param commandKey   幂等命令键
 * @param locationCode 库位业务键，全局唯一
 * @param label        库位名称说明
 */
public record LocationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String locationCode,
        @NotBlank @Size(max = 128) String label) {
}
