package com.example.starter.firmware.api;

import java.util.List;

/**
 * MODULE_BLOCKED 阻断明细视图（只读诊断，查询不修改状态）。
 */
public record MissingModuleView(String module, String deviceVersion, String requiredVersion) {
}
