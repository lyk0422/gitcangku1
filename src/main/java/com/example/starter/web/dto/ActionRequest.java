package com.example.starter.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 批准或撤销请求。
 *
 * @param projectId 撤销时必填，研究组只能撤销本项目的申请；批准可留空
 */
public record ActionRequest(
        @NotBlank String requestNo,
        String projectId) {
}
