package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单 PATH_BLOCKED 判定历史响应。
 */
public record PathBlockedHistoryResponse(long releaseId, List<PathBlockedRecordView> records) {
}
