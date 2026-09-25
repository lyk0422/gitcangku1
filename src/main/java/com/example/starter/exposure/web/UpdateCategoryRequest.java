package com.example.starter.exposure.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改公告活动类别请求；类别修改后活动版本 +1，旧同意不迁移到新类别。
 *
 * @param requestId 写操作全局唯一幂等键
 * @param category  新活动类别
 */
public record UpdateCategoryRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String category
) {
}
