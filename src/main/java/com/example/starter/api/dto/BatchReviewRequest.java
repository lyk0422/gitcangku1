package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量审查请求。整批在一个事务内校验并创建结果：任一航线不存在、版本不符或
 * 缺预测，整批不创建任何结果。条目在规范化（按 routeId 排序去重）后参与幂等指纹。
 *
 * @param items           航线项（1~20 个，routeId 不得重复）
 * @param airspaceVersion 明确的空域版本（须为当前版本）
 * @param forecastVersion 预测气象版本；涉及气象标准区域时必填，缺失返回 422
 * @param operator        操作者标识，参与幂等指纹
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record BatchReviewRequest(
        @NotNull @Valid @Size(min = 1, max = 20) List<BatchReviewItemDto> items,
        @NotNull Long airspaceVersion,
        Long forecastVersion,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
