package com.example.starter.batch.dto;

import com.example.starter.batch.BatchStatus;

import java.time.Instant;

/**
 * 召回响应。version 为召回代次；recallStatus 为该代召回记录状态（ACTIVE/LIFTED）；
 * batchStatus 为批次当前状态（召回提交时恒为 RECALLED）。
 */
public record RecallResponse(
        String batchKey,
        String actorId,
        String reason,
        int version,
        String recallStatus,
        BatchStatus batchStatus,
        Instant createdAt
) {
}
