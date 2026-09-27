package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 驻留迁移请求：创建新的授权代次并把明确列出的记录原子复制到目标区域，源代次同时撤回。
 * 迁移集合含已清除、无授权或不同区域对象时整次 422，源和目标均不留下部分副本。
 *
 * @param requestId        幂等请求标识
 * @param subjectKey       主体标识（合成字符串）
 * @param purpose          用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param targetRegionCode 目标驻留区域代码（必须预先登记）
 * @param recordKeys       明确列出待迁移的记录键，非空
 */
public record MigrationRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 64) String targetRegionCode,
        @NotEmpty List<@NotBlank @Size(max = 128) String> recordKeys) {
}
