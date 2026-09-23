package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销委托边请求：按 delegationKey 撤销单条委托边，只影响撤销提交后的写入。
 *
 * @param requestId     幂等请求标识
 * @param delegationKey 待撤销委托边的全局唯一键
 */
public record DelegationRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegationKey) {
}
