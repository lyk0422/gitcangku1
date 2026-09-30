package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 销毁申请请求。commandKey 同时作为销毁申请业务键 requestId，全局唯一；
 * 仅当前保管人可申请；存在证物自身或所属案件的有效冻结时返回 409，证物状态不变。
 *
 * @param commandKey 幂等命令键（即销毁申请 requestId）
 * @param reason     销毁原因，非空
 */
public record DestructionCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
