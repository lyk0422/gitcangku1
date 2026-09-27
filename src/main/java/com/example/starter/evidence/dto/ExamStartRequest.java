package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 开始检验请求。仅预约操作人可对 RESERVED 预约开始检验。
 *
 * @param commandKey 幂等命令键（requestId）
 */
public record ExamStartRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
