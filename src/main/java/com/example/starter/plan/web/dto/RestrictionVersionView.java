package com.example.starter.plan.web.dto;

/**
 * 发布快照中固化的区段限制版本项，按 sectionId 字典序稳定排序。
 */
public record RestrictionVersionView(String sectionId, int version) {
}
