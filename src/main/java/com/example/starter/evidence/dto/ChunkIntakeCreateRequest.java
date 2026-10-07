package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建分块入库待接收记录请求。创建时固定总字节数与完整 SHA-256 摘要；
 * 入库人取自 X-Actor-Id，仅其可补块、完成或放弃。
 *
 * @param commandKey  幂等命令键（全局 requestId）
 * @param intakeKey   分块入库业务键，全局唯一
 * @param evidenceKey 完成接收后写入的证物业务键
 * @param caseKey     所属案件键
 * @param category    证物类别
 * @param sealNo      封条编号
 * @param totalBytes  声明的总字节数，1至65536
 * @param sha256      完整内容的 SHA-256 摘要（64位十六进制，大小写均可，存储小写）
 */
public record ChunkIntakeCreateRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String intakeKey,
        @NotBlank @Size(max = 64) String evidenceKey,
        @NotBlank @Size(max = 64) String caseKey,
        @NotBlank @Size(max = 64) String category,
        @NotBlank @Size(max = 64) String sealNo,
        @NotNull @Min(1) @Max(65536) Integer totalBytes,
        @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256) {
}
