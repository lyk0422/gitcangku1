package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量创建冻结令响应：与请求顺序一致的冻结令视图。
 */
public record BatchFreezeResponse(List<FreezeOrderView> freezes) {
}
