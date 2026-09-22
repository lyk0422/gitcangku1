package com.example.starter.artifact.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 锁定请求：指定精确根版本与期望的仓库版本。
 *
 * @param requestId                  全局唯一幂等请求 id
 * @param rootName                   根制品名称，必须存在且未撤回
 * @param rootVersion                根制品精确版本，正整数
 * @param expectedRepositoryVersion  期望的仓库版本，不符返回 409
 */
public record LockRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*") String rootName,
        @Min(1) int rootVersion,
        @Min(0) long expectedRepositoryVersion) {
}
