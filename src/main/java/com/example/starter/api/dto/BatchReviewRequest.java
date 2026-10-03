package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量审核请求：全部航线共用同一个预测气象版本。
 * 任一航线缺预测或校验失败则整批不创建任何结果（事务整体回滚）。
 *
 * @param items           待审核航线项（至少一个）
 * @param forecastVersion 预测气象版本；任一路径经过气象区域时必填
 * @param operator        操作者标识，参与幂等指纹计算
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record BatchReviewRequest(
        @NotNull @Valid @Size(min = 1, max = 100) List<BatchReviewItemDto> items,
        Long forecastVersion,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}