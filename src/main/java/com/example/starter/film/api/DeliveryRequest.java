package com.example.starter.film.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 交付请求；只引用审核通过的不可变母版编号。
 *
 * @param masterNo  母版编号
 * @param requestId 客户端幂等请求号
 */
public record DeliveryRequest(@NotBlank String masterNo, @NotBlank String requestId) {
}
