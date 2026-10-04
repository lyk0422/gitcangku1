package com.example.starter.plan.web.dto;

/**
 * 冻结牵引快照项：发布时刻的牵引方式与该区段档案值、版本。
 */
public record TractionSnapshotItem(String trainNo, String tractionMode, String sectionId,
                                   boolean electrified, boolean dieselAllowed, int sectionVersion) {
}