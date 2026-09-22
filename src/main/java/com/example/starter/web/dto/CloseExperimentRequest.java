package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 关闭实验请求。
 *
 * @param requestId 全局唯一写操作请求编号
 */
public record CloseExperimentRequest(
        @NotBlank String requestId
) {
}
