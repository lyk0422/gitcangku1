package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.MigrationRule;
import com.example.starter.firmware.domain.MigrationRuleCodec;
import com.example.starter.firmware.domain.ReleaseOrder;

import java.util.List;

/**
 * 发布单视图。migrationRules 为有序配置迁移规则，顺序是发布单身份一部分。
 */
public record ReleaseView(long releaseId, int version, String model, String fromVersion, String toVersion,
                          int ratio, String status, int sampleFloor, int failureThresholdPercent,
                          int monitorRound, List<MigrationRule> migrationRules) {

    public static ReleaseView of(ReleaseOrder order) {
        return new ReleaseView(order.id(), order.version(), order.model(), order.fromVersion(),
                order.toVersion(), order.ratio(), order.status().name(), order.sampleFloor(),
                order.failureThresholdPercent(), order.monitorRound(),
                MigrationRuleCodec.fromJson(order.migrationRules()));
    }
}
