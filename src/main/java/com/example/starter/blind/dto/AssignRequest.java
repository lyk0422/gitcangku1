package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 参与者登记请求：首次登记原子领取按区组、席位顺序排列的第一个空位。
 */
public record AssignRequest(
        @NotBlank String requestId,
        @NotBlank String participantId) {
}
