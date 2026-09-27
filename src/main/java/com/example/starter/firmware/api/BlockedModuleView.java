package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.BlockedModule;

/**
 * 拉取被模块兼容阻断时返回的单个缺失/低版本模块明细，按模块名稳定排序。
 * deviceVersion 为 null 表示设备未安装该模块（MISSING）。
 */
public record BlockedModuleView(String moduleName, String deviceVersion, String requiredVersion, String reason) {

    public static BlockedModuleView of(BlockedModule blocked) {
        return new BlockedModuleView(blocked.moduleName(), blocked.deviceVersion(),
                blocked.requiredVersion(), blocked.reason().name());
    }
}
