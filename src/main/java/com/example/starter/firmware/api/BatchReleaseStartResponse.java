package com.example.starter.firmware.api;

import java.util.List;

/**
 * 批量启动发布响应：按提交顺序返回全部发布单视图，以及经紧急例外放行所命中的冻结令ID集合。
 */
public record BatchReleaseStartResponse(List<ReleaseView> releases, List<Long> emergencyFreezeIds) {
}
