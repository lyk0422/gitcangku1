package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单模块阻断诊断列表（只读），按阻断记录ID升序。
 */
public record BlockListResponse(long releaseId, List<ModuleBlockView> blocks) {
}
