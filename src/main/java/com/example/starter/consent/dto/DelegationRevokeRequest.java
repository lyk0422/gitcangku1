package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销委托边请求：按 delegationKey 撤销；只影响后续写入，不回滚历史记录。
 *
 * @param requestId     幂等请求标识
 * @param delegationKey 委托边业务唯一键
 */
public record DelegationRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegationKey) {
}
