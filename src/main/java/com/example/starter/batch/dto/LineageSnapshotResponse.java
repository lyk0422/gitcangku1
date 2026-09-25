package com.example.starter.batch.dto;

import com.example.starter.batch.SegregationLevel;

import java.time.Instant;
import java.util.List;

/**
 * 批次血缘快照：批次自身当前成分版本，加上两类血缘边的不可变快照。
 * splitParents 为该批由拆分产生时的父批链信息（通常至多一个直接父批）；
 * mergedSources 为合批并入该容器的全部来源（按合批提交顺序）；
 * splitChildren/mergedInto 用于向上向下追溯。所有版本均为当时落定的不可变成分版本。
 */
public record LineageSnapshotResponse(
        String batchKey,
        ComponentNode self,
        List<SplitEdge> splitParents,
        List<SplitEdge> splitChildren,
        List<MergeEdge> mergedSources,
        MergeEdge mergedInto
) {

    /**
     * 血缘节点的成分画像。
     */
    public record ComponentNode(
            String batchKey,
            String batchNo,
            com.example.starter.batch.BatchStatus status,
            int componentVersion,
            List<String> allergenCodes,
            SegregationLevel segregationLevel
    ) {
    }

    /**
     * 拆分血缘边快照：成分版本为子批拆出时父批的当前版本。
     */
    public record SplitEdge(
            String parentBatchKey,
            String childBatchKey,
            int parentComponentVersion,
            Instant createdAt
    ) {
    }

    /**
     * 合批血缘边快照：记录来源当时成分版本与并入数量。
     */
    public record MergeEdge(
            String allergenKey,
            String targetBatchKey,
            String sourceBatchKey,
            int sourceComponentVersion,
            java.math.BigDecimal quantity,
            Instant createdAt
    ) {
    }
}
