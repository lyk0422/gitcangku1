package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备任务历史查询响应：按发布单与尝试序号升序的全部尝试。
 */
public record TaskHistoryResponse(String deviceId, List<TaskAttemptView> attempts) {
}
