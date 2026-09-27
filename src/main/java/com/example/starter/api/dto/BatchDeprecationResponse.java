package com.example.starter.api.dto;

import java.util.List;

/**
 * 批量登记弃用窗口响应。
 *
 * @param repositoryVersion 本次批量登记完成后的仓库版本号
 * @param windows 已登记的弃用窗口，按请求顺序返回
 */
public record BatchDeprecationResponse(
        long repositoryVersion,
        List<DeprecationResponse> windows) {
}
