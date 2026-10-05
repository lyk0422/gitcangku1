package com.example.starter.observation;

import java.util.List;

/**
 * 批量禁运设置响应：整批原子生效后的完整结果，重放时原样返回。
 *
 * @param requestId 请求标识
 * @param operator  操作者标识
 * @param itemCount 本次生效的禁运条目数（实际值，等于 items 大小）
 * @param items     各簇禁运设置结果，按 observationId 字典序稳定排序
 */
public record EmbargoBatchResponse(
        String requestId,
        String operator,
        int itemCount,
        List<EmbargoItemResponse> items) {
}
