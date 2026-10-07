package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建分块待接收记录请求。固定总字节数 1..65536 与完整 SHA-256 摘要，创建后均不可修改。
 *
 * @param requestId   全局请求幂等键（非空，全局唯一）
 * @param intakeKey   分块接收业务键（即完成后的证物业务键）
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param totalLength 固定总字节数，1..65536，单位字节
 * @param sha256      完整字节流 SHA-256 十六进制摘要（64 位，大小写均可，服务端按小写存储）
 */
public record ChunkIntakeCreateRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String intakeKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @NotNull @Min(1) @Max(65536) Integer totalLength,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256) {
}
