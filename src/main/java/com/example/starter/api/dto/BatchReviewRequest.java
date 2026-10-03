package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量审查请求：全部航线校验通过才创建结果；任一航线缺预测（或版本不符、
 * 参数非法）则整批失败回滚，不创建任何审查结果。
 *
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 * @param operator  操作者标识，计入幂等指纹
 * @param items     待审查航线列表（至少一条，结果按提交顺序返回）
 */
public record BatchReviewRequest(
        @NotBlank @Size(max = 64) String requestId,
        @NotBlank @Size(max = 64) String operator,
        @NotNull @Valid @Size(min = 1, max = 100) List<BatchReviewItemDto> items) {
}