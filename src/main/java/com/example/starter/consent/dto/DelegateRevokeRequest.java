package com.example.starter.consent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 委托撤销请求：撤销只影响后续查询，已生成查询快照固化委托与授权版本。
 *
 * @param requestId   幂等请求标识
 * @param delegateKey 待撤销的委托键
 */
public record DelegateRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String delegateKey) {
}
