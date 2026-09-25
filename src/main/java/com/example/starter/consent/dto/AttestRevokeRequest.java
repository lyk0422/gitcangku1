package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 证明撤销请求：撤销某接收方在指定“用途＋代次”作用域上的当前生效证明。
 * 撤销只影响后续查询，已生成的查询快照及其授权代次、证明版本不可改写。
 *
 * @param requestId   幂等请求标识
 * @param recipientId 接收方标识
 * @param purpose     证明作用域用途
 * @param epoch       证明作用域代次
 */
public record AttestRevokeRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String recipientId,
        @NotNull Purpose purpose,
        @NotNull @Min(1) Integer epoch) {
}
