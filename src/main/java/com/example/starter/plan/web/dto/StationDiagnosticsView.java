package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 站台诊断视图：当前配置版本、指定运营日全部生效站停（按到达时刻稳定排序）、
 * 以及涉及该站的不可变发布快照历史（按快照 id 升序）。只读，不改变任何状态。
 * configured=false 时配置数值字段无意义（调用方应收到 404，正常不会出现该响应）。
 */
public record StationDiagnosticsView(String stationId, boolean configured,
                                     Integer minDwellMinutes, Integer platformCapacity,
                                     Integer version, String operator, LocalDate opDate,
                                     List<ActiveStationDwellView> activeDwells,
                                     List<PublishSnapshotView> snapshots) {
}
