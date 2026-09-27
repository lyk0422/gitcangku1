package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ModuleBlockItem;
import com.example.starter.firmware.domain.ModuleBlockRecord;

import java.util.List;

/**
 * 模块兼容阻断视图：拉取时设备模块不满足目标固件完整依赖集合且未被豁免。
 * missingModules 按模块名升序稳定排序，含设备实际版本（未安装为 null）与最低要求版本。
 */
public record ModuleBlockView(String code, long blockId, long releaseId, String deviceId,
                              int matrixVersion, int exemptionVersion, String moduleDigest,
                              String blockedAtUtc, List<MissingModule> missingModules) {

    public static final String CODE = "MODULE_BLOCKED";

    /**
     * 缺失或低版本模块明细。
     *
     * @param module        模块名
     * @param deviceVersion 设备当前模块版本，未安装为 null
     * @param minVersion    最低要求版本
     */
    public record MissingModule(String module, String deviceVersion, String minVersion) {
    }

    public static ModuleBlockView of(ModuleBlockRecord record, List<ModuleBlockItem> items) {
        return new ModuleBlockView(CODE, record.id(), record.releaseId(), record.deviceId(),
                record.matrixVersion(), record.exemptionVersion(), record.moduleDigest(),
                record.blockedAtUtc(),
                items.stream()
                        .map(item -> new MissingModule(item.module(), item.deviceVersion(),
                                item.minVersion()))
                        .toList());
    }
}
