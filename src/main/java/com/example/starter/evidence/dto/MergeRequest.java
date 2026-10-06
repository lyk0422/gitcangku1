package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 证物合并请求：当前保管人将两个误建的证物号合并为一个身份。
 * 主号保留身份继续流转，被合并号转为 ALIAS（终态）。
 *
 * @param requestId              全局请求标识（幂等键）：同参重放返回首次完整响应，异参返回 409
 * @param primaryKey             主号证物业务键（保留身份的一方）
 * @param aliasKey               被合并证物业务键，合并后转为 ALIAS
 * @param expectedPrimaryVersion 主号期望版本（须等于当前版本，否则 409）
 * @param expectedAliasVersion   副号期望版本（须等于当前版本，否则 409）
 */
public record MergeRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String primaryKey,
        @NotBlank @Size(max = 64) String aliasKey,
        @NotNull @Min(1) Long expectedPrimaryVersion,
        @NotNull @Min(1) Long expectedAliasVersion) {
}
