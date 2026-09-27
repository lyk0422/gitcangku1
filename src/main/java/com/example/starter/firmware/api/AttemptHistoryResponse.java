package com.example.starter.firmware.api;

import java.util.List;

/**
 * 任务全部尝试代次历史响应（只读）。
 */
public record AttemptHistoryResponse(long taskId, List<AttemptView> attempts) {
}
