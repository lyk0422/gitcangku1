package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ModuleSnapshot;

import java.util.List;

/**
 * 设备当前模块清单视图（只读），模块按模块名稳定排序。
 */
public record DeviceModulesView(String deviceId, String reportedAtUtc, List<Item> modules) {

    /**
     * 设备模块条目。
     */
    public record Item(String moduleName, String version, String digest) {
        public static Item of(ModuleSnapshot snapshot) {
            return new Item(snapshot.moduleName(), snapshot.version(), snapshot.digest());
        }
    }
}
