package com.example.starter.plan.web.dto;

import java.util.List;

/**
 * 接续历史查询响应：事件按 id 升序（即发生顺序）排列。
 */
public record ConnectionHistoryResponse(long connectionId, List<ConnectionEventView> events) {
}