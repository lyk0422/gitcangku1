package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 记录写入请求：仅当前有效代次可写入；处理方须提交委托链及各边版本。
 *
 * @param requestId      幂等请求标识
 * @param subjectKey     主体标识（合成字符串）
 * @param purpose        用途：RESEARCH 研究 / PERSONALIZATION 个性化
 * @param recordKey      记录键，同一代内唯一
 * @param payload        记录内容（合成字符串）
 * @param delegationPath 处理方键有序链：从主体的直接处理方开始、到调用方结束；
 *                       主体自己写入时为空列表
 * @param edgeVersions   与 delegationPath 逐边对应的边版本号；主体自己写入时为空列表
 */
public record RecordWriteRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String subjectKey,
        @NotNull Purpose purpose,
        @NotBlank @Size(max = 128) String recordKey,
        @NotBlank @Size(max = 8192) String payload,
        @NotNull @Size(max = 5) List<@NotBlank @Size(max = 128) String> delegationPath,
        @NotNull @Size(max = 5) List<@NotNull Integer> edgeVersions) {
}
