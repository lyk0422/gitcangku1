package com.example.starter.plan.web.dto;

import java.time.Instant;

/**
 * 功率快照视图：发布/改签裁决时冻结的绑定、声明功率与上限版本，历史不漂移。
 */
public record PowerSnapshotView(String sectionId, String substationKey, int limitVersion,
                                int limitKw, int powerKw, Instant startUtc, Instant endUtc) {
}
