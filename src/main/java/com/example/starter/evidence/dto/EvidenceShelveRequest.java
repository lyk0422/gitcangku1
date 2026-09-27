package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 证物上架请求：将一件封存证物放入指定启用库位（测试与初始化库存使用）。
 * 仅当前保管人可操作，证物须 SEALED；上架后版本号加 1。
 *
 * @param commandKey   幂等命令键
 * @param locationCode 目标库位编码，必须为启用状态
 */
public record EvidenceShelveRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String locationCode) {
}
