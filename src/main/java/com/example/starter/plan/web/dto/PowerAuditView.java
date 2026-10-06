package com.example.starter.plan.web.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 功率裁决快照视图：冻结裁决时的上限版本、上限值与裁决后功率切片，历史不漂移。
 * opDate 为切片所属运营日，无在效占用时为 null。
 */
public record PowerAuditView(long id, String opType, String substationKey, LocalDate opDate,
                             int capVersion, int capKw, List<PowerSliceView> slices,
                             long createdAt) {
}
