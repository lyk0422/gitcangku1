package com.example.starter.firmware.domain;

/**
 * 拉取时单个不兼容模块的阻断明细，按模块名稳定排序返回。
 *
 * @param moduleName      缺失或低版本的模块名
 * @param deviceVersion   设备上报版本；设备未安装该模块时为 null（MISSING）
 * @param requiredVersion 目标固件完整依赖集合要求的最低版本
 * @param reason          MISSING 缺失；VERSION_TOO_LOW 版本过低
 */
public record BlockedModule(String moduleName, String deviceVersion, String requiredVersion,
                            ModuleBlockReason reason) {
}
