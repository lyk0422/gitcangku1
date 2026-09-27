package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 完成检验请求。必须记录结果摘要并填写重封新封签编号与新封签校验值，证物才可回到在库 SEALED。
 *
 * @param commandKey    幂等命令键（requestId）
 * @param resultSummary 检验结果摘要，非空
 * @param newSealNo     重封新封签编号，非空且必须不同于开始时锁定的原封签编号
 * @param newSealCheck  新封签校验值（检验室复核），非空
 */
public record ExamCompleteRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 1024) String resultSummary,
        @NotBlank @Size(max = 64) String newSealNo,
        @NotBlank @Size(max = 128) String newSealCheck) {
}
