package com.example.starter.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量审查请求。先计算全部航线的最终容量与关闭影响，
 * 任一航线被拒绝则整批不批准（无任何审查记录或状态变更落库）。
 *
 * @param airspaceVersion 明确的空域版本（整批共用）
 * @param items           待审查航线及其明确版本，1~50 条，routeId 不得重复
 * @param requestId       写操作全局唯一请求标识，用于幂等重放
 */
public record BatchReviewRequest(
        @NotNull Long airspaceVersion,
        @NotNull @Valid @Size(min = 1, max = 50) List<BatchReviewItem> items,
        @NotBlank @Size(max = 64) String requestId) {

    /**
     * 批量审查单项。
     *
     * @param routeId      航线标识
     * @param routeVersion 明确的航线版本
     */
    public record BatchReviewItem(
            @NotBlank @Size(max = 64) String routeId,
            @NotNull Integer routeVersion) {
    }
}
