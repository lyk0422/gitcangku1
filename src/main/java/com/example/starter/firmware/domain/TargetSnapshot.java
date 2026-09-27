package com.example.starter.firmware.domain;

import java.util.List;

/**
 * 发布单目标快照（创建时解析一次并固化，后续标签/设备修订不追溯重算）。
 *
 * @param releaseId        所属发布单ID
 * @param expression       固化的规范化标签表达式
 * @param labelDictVersion 解析时刻标签字典版本
 * @param targetDevices    规范排序（字典序）的目标设备ID快照
 * @param resolvedAtUtc    解析时刻，UTC，ISO-8601 格式
 */
public record TargetSnapshot(long releaseId, LabelExpression expression, long labelDictVersion,
                             List<String> targetDevices, String resolvedAtUtc) {
}
