package com.example.starter.firmware.api;

import java.util.List;

/**
 * 发布单豁免历史（只读）：每次修改产生一个版本，按版本升序，历史不改写。
 */
public record ExemptionHistoryResponse(long releaseId, List<ExemptionVersionView> versions) {

    /**
     * 单版豁免快照。
     */
    public record ExemptionVersionView(int exemptionVersion, String createdAtUtc,
                                       List<ExemptionListResponse.ExemptionGroup> exemptions) {
    }
}
