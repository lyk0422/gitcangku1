package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 交接发起请求。仅当前保管人可发起，接收人必须与发起人不同。
 *
 * @param commandKey  幂等命令键
 * @param toCustodian 指定接收人
 */
public record TransferInitiateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String toCustodian) {
}
