package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 库位创建请求。locationCode 全局唯一，初始状态 ACTIVE、库存版本 0。
 *
 * @param commandKey   幂等命令键
 * @param locationCode 库位编码，全局唯一
 * @param description  库位描述；null 表示未填写
 */
public record LocationCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String locationCode,
        @Size(max = 512) String description) {
}
