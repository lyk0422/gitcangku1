package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消检验预约请求。仅允许未开始（RESERVED）的预约，取消后释放检验台与证物占用。
 *
 * @param commandKey 幂等命令键
 * @param reason     取消原因；null 表示未填写
 */
public record ExamCancelRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @Size(max = 512) String reason) {
}
