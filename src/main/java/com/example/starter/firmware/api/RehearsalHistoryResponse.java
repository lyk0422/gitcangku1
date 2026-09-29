package com.example.starter.firmware.api;

import java.util.List;

/**
 * 演练历史响应：按开始时刻（并列按演练ID）稳定排序，只读不改写状态。
 */
public record RehearsalHistoryResponse(String model, String targetVersion, List<RehearsalView> rehearsals) {
}
