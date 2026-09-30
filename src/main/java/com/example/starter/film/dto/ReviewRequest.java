package com.example.starter.film.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 整片审核请求。
 *
 * @param requestId 幂等请求ID，非空，最长 64
 */
public record ReviewRequest(
        @NotBlank @Size(max = 64)
        String requestId
) {
}
