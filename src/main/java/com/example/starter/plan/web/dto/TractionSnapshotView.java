package com.example.starter.plan.web.dto;

/**
 * 发布时冻结的牵引快照视图：牵引方式与该段档案值、版本；草稿计划无快照。
 */
public record TractionSnapshotView(String trainNo, String tractionMode, String sectionId,
                                   boolean electrified, boolean dieselAllowed,
                                   int sectionVersion) {
}