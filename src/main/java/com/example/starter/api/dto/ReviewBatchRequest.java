package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量审核请求。任一条目校验失败（含任一航线缺预测）整批不创建结果，
 * 在一个事务中先校验完整最终状态再统一提交。
 *
 * @param items     审核条目（至少一条，结果按提交顺序返回）
 * @param operator  操作者标识，参与幂等指纹
 * @param requestId 写操作全局唯一请求标识，用于幂等重放
 */
public record ReviewBatchRequest(
        @NotNull @Valid @Size(min = 1, max = 100) List<ReviewBatchItem> items,
        @NotBlank @Size(max = 64) String operator,
        @NotBlank @Size(max = 64) String requestId) {
}
