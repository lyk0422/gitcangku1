package com.example.starter.firmware.api;

import java.util.List;

/**
 * 兼容判定历史响应（只读诊断）。
 */
public record CheckHistoryResponse(List<CheckView> checks) {
}
