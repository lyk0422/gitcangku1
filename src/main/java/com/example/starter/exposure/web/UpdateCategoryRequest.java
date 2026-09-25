package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改活动类别请求：类别变更后活动版本 +1，旧类别同意不会迁移到新类别。
 *
 * @param requestId   写操作全局唯一幂等键
 * @param campaignId  公告编号（路径参数一致携带，服务层校验一致性）
 * @param newCategory 新活动类别
 */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String campaignId,
        @NotBlank @Size(max = 64) String newCategory
) {
}
