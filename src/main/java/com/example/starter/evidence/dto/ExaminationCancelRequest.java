package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 取消检验预约命令。仅未开始（SCHEDULED）预约可取消；取消释放检验台容量与证物占用。
 *
 * @param commandKey 幂等命令键
 * @param reason     取消原因，非空
 */
public record ExaminationCancelRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String reason) {
}
