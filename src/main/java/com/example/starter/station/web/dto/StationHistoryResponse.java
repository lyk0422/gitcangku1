package com.example.starter.station.web.dto;

import java.util.List;

/**
 * 站台配置不可变版本历史，按版本号升序稳定返回；规则修订不改写既有版本。
 */
public record StationHistoryResponse(String stationId, List<VersionItem> versions) {

    /**
     * 单个不可变版本。
     */
    public record VersionItem(int version, int minDwellMinutes, int platformCapacity,
                              String operatorId, long createdAt) {
    }
}
