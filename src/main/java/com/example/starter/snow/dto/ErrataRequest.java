package com.example.starter.snow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 读数勘误请求：只追加勘误与复核结论，不修改原读数。
 *
 * @param requestId 幂等键
 * @param reviewer 复核人
 * @param note 勘误说明
 */
public record ErrataRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String reviewer,
        @NotBlank @Size(max = 500) String note) {
}