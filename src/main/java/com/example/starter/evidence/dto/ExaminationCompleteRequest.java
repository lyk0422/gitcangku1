package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 检验完成命令。必须记录结果摘要与新封签编号（新封签校验），
 * 通过后在同一事务追加新封签版本并将证物回到在库（SEALED）。
 *
 * @param commandKey    幂等命令键
 * @param resultSummary 检验结果摘要，非空
 * @param newSealNo     完成时校验的新封签编号，非空
 */
public record ExaminationCompleteRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 1024) String resultSummary,
        @NotBlank @Size(max = 64) String newSealNo) {
}
