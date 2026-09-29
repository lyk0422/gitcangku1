package com.example.starter.firmware.api;

import java.util.List;

/**
 * 演练历史响应（只读）：按型号（可再按目标/回退版本）过滤，演练按ID稳定排序。
 */
public record RehearsalHistoryResponse(String model, String targetVersion, String rollbackVersion,
                                       int totalCount, List<RehearsalView> rehearsals) {
}
