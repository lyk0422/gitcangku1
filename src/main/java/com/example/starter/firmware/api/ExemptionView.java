package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.ModuleExemption;

import java.util.List;

/**
 * 发布单豁免某一版本的完整视图（只读）。
 *
 * @param releaseId     发布单ID
 * @param version       豁免版本号，从1开始，每次整体修改成功加一；无任何修改为0且 entries 为空
 * @param createdAtUtc  本版本登记时刻，UTC，ISO-8601；版本0为 null
 * @param entries       本版本全部豁免条目，按设备组、模块名排序
 */
public record ExemptionView(long releaseId, int version, String createdAtUtc, List<Item> entries) {

    /**
     * 豁免条目视图。expiresAtUtc 为到期时刻（UTC），左闭右开，到期后该条目不再生效。
     */
    public record Item(String deviceGroup, String moduleName, String expiresAtUtc) {
        public static Item of(ModuleExemption exemption) {
            return new Item(exemption.deviceGroup(), exemption.moduleName(), exemption.expiresAtUtc());
        }
    }
}
