package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建质量标记请求：对观测当前版本提交质量问题类别与说明，生成待复核标记。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param flagKey     质量标记标识，同一观测内唯一
 * @param category    质量问题类别
 * @param description 质量问题说明
 * @param role        标记提交人角色；复核人角色必须与其不同
 */
public record CreateQualityFlagRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String flagKey,
        @NotNull QualityCategory category,
        @NotBlank @Size(max = 1024) String description,
        @NotBlank @Size(max = 64) String role) {
}
