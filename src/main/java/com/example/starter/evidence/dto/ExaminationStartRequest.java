package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检验开始命令（仅幂等键）。开始时在同一事务内将证物转为 EXAMINATION、
 * 写入 STARTED 临时链记录并锁定原封签版本。
 *
 * @param commandKey 幂等命令键
 */
public record ExaminationStartRequest(
        @NotBlank @Size(max = 64) String commandKey) {
}
