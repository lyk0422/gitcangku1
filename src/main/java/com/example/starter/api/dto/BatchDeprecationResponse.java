package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量设置弃用窗口结果。
 *
 * @param requestedCount 要求写入的窗口数量（入参条数，含对同一版本的重复键以 422 拒绝时不产生本响应）
 * @param appliedCount   实际写入的窗口数量（成功时与 requestedCount 相同）
 * @param windows        实际写入后的窗口视图，按制品、版本稳定排序
 */
public record BatchDeprecationResponse(
        int requestedCount,
        int appliedCount,
        List<DeprecationResponse> windows) {
}
