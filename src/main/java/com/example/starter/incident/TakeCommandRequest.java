package com.example.starter.incident;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 接管请求，commandKey 为幂等键。
 */
public record TakeCommandRequest(
        @NotBlank @Size(max = 128) String commandKey) {
}
