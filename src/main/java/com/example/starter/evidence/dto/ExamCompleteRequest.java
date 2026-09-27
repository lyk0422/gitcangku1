package com.example.starter.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 完成检验请求。必须记录结果摘要与新封签校验（新封签编号且校验通过），
 * 证物才能回到在库（SEALED）。
 *
 * @param commandKey    幂等命令键
 * @param resultSummary 检验结果摘要，非空
 * @param newSealNo     新封签编号，非空
 * @param newSealIntact 新封签校验结果：必须为 true 才能完成
 * @param newSealNote   新封签校验说明；null 表示未填写
 */
public record ExamCompleteRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 512) String resultSummary,
        @NotBlank @Size(max = 64) String newSealNo,
        @NotNull Boolean newSealIntact,
        @Size(max = 512) String newSealNote) {
}
