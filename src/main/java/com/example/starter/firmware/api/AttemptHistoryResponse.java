package com.example.starter.firmware.api;

import java.util.List;

/**
 * 设备任务尝试历史响应：按尝试序号升序列出某设备在某发布单的全部尝试。
 */
public record AttemptHistoryResponse(long releaseId, String deviceId, List<AttemptView> attempts) {
}
