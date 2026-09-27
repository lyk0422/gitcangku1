package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消检验预约请求。仅未开始（RESERVED）的预约可取消，取消后释放检验台与证物占用。
 *
 * @param commandKey 幂等命令键（requestId）
 */
public record ExamCancelRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
