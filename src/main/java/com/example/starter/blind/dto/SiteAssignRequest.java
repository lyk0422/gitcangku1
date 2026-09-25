package com.example.starter.blind.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 中心作用域分配请求体。
 *
 * @param assignmentKey 分配业务键，全局唯一；绑定操作者、受试者、中心代次与全部状态字段，
 *                      同键成功重放首次响应，失败不占键
 */
public record SiteAssignRequest(
        @NotBlank(message = "assignmentKey 不能为空")
        @Size(max = 64, message = "assignmentKey 长度不能超过 64 个字符")
        String assignmentKey
) {
}
