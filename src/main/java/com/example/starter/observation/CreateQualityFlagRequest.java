package com.example.starter.observation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 创建质量标记请求：任意角色对某观测当前版本提交，生成待复核标记。
 *
 * @param requestId   全局唯一请求标识（幂等去重键）
 * @param flagKey     质量标记唯一标识
 * @param category    质量问题类别：SENSOR_ANOMALY / HUMAN_MISOPERATION / ENVIRONMENTAL_INTERFERENCE
 * @param description 质量问题说明
 * @param submittedBy 标记提交角色；复核角色必须与之不同
 */
public record CreateQualityFlagRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotBlank @Size(max = 64) String flagKey,
        @NotNull QualityCategory category,
        @NotBlank @Size(max = 1024) String description,
        @NotBlank @Size(max = 64) String submittedBy) {
}
