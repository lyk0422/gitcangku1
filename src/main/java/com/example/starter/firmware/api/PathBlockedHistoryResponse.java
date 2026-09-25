package com.example.starter.firmware.api;

import java.util.List;

/**
 * PATH_BLOCKED 拦截历史响应。
 */
public record PathBlockedHistoryResponse(long releaseId, List<PathBlockedView> records) {
}
