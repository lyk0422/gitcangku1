package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量创建冻结令响应：按提交顺序返回全部冻结令视图。
 */
public record FreezeBatchResponse(List<FreezeView> freezes) {
}
