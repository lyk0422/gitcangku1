package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单当前豁免视图（只读）：exemptionVersion 为当前豁免版本，
 * exemptions 按（设备组，模块）升序稳定排序。
 */
public record ExemptionListResponse(long releaseId, int exemptionVersion,
                                    List<ExemptionGroup> exemptions) {

    /**
     * 单条豁免：设备组、显式模块列表（升序）与到期时刻（UTC，ISO-8601）。
     */
    public record ExemptionGroup(String deviceGroup, List<String> modules, String expiresAtUtc) {
    }
}
