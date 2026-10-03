package com.example.starter.bridge.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 撤销请求。
 *
 * @param requestId 撤销幂等键
 */
public record CancelRequest(@NotBlank @Size(max = 64) String requestId) {
}