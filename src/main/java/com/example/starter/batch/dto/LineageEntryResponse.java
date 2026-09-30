package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.util.List;

/**
 * 血缘查询条目：批次自身状态、规范化过敏原集合、当前清洁代次 + 导致其不可用的召回祖先业务键。
 * relationType 为该条目相对查询根的血缘关系来源：SPLIT（拆分）/MERGE（合批）。
 * unavailableDueToRecalledAncestor 为 null 表示没有召回祖先；
 * 批次自身被直接召回（RECALLED）不算祖先召回，该字段仍为 null。
 */
public record LineageEntryResponse(
        String batchKey,
        String batchNo,
        BatchStatus status,
        List<String> allergens,
        int cleanGeneration,
        String relationType,
        String unavailableDueToRecalledAncestor
) {
}
