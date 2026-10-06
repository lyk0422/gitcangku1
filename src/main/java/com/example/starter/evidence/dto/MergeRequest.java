package com.example.starter.evidence.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 证物身份合并请求。仅两证物当前保管人可发起；主号保留，被合并号转为 ALIAS。
 * 两个证物须分别携带期望版本（旧版本 409）；两键必须不同。
 *
 * @param commandKey            幂等命令键（全局 requestId）
 * @param primaryKey            合并后保留的主号证物业务键
 * @param primaryExpectedVersion 主号期望版本
 * @param aliasKey              被合并转为别名的证物业务键
 * @param aliasExpectedVersion  别名期望版本
 */
public record MergeRequest(
        @NotBlank @Size(max = 64) String commandKey,
        @NotBlank @Size(max = 64) String primaryKey,
        @NotNull @Min(1) Long primaryExpectedVersion,
        @NotBlank @Size(max = 64) String aliasKey,
        @NotNull @Min(1) Long aliasExpectedVersion) {
}
