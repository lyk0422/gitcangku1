package com.example.starter.firmware.api;

import com.example.starter.firmware.domain.LabelExpression;
import com.example.starter.firmware.domain.TargetSnapshot;

import java.util.List;

/**
 * 发布单目标快照视图（只读）。快照创建时解析一次并固化：
 *
 * @param mustInclude      固化的必须包含标签（规范化排序）
 * @param mustExclude      固化的不得包含标签（规范化排序）
 * @param includeAny       固化的至少包含一项标签（规范化排序）
 * @param labelDictVersion 解析时刻标签字典版本（要求参照的字典版本，后续修订不改变本值）
 * @param targetCount      目标快照实际设备数（实际值）
 * @param targetDevices    规范排序（字典序）的目标设备ID
 * @param resolvedAtUtc    解析时刻，UTC，ISO-8601 格式
 */
public record TargetSnapshotView(List<String> mustInclude, List<String> mustExclude, List<String> includeAny,
                                 long labelDictVersion, int targetCount, List<String> targetDevices,
                                 String resolvedAtUtc) {

    public static TargetSnapshotView of(TargetSnapshot snapshot) {
        LabelExpression expression = snapshot.expression();
        return new TargetSnapshotView(expression.mustInclude(), expression.mustExclude(),
                expression.includeAny(), snapshot.labelDictVersion(), snapshot.targetDevices().size(),
                snapshot.targetDevices(), snapshot.resolvedAtUtc());
    }
}
