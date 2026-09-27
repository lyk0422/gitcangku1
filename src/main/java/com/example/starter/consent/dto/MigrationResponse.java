package com.example.starter.consent.dto;

import java.time.Instant;
import java.util.List;

import com.example.starter.consent.Purpose;

/**
 * 跨区域迁移提交结果：数量字段同时给出要求值、实际值与差额，成功时差额为 0。
 *
 * @param migrationId          迁移单号（与 requestId 相同）
 * @param subjectKey           主体标识
 * @param purpose              用途
 * @param sourceEpoch          源授权代次（提交后已撤回）
 * @param sourceRegion         源驻留区域代码
 * @param targetEpoch          新授权代次（提交后有效）
 * @param targetRegion         目标驻留区域代码
 * @param requestedObjectCount 要求复制的对象数量（要求值）
 * @param migratedObjectCount  实际复制的对象数量（实际值）
 * @param delta                差额 = 实际值 - 要求值，成功为 0
 * @param recordKeys           实际复制的对象键（按字典序）
 * @param status               COMMITTED 已提交
 * @param committedAt          提交时间（UTC）
 */
public record MigrationResponse(String migrationId, String subjectKey, Purpose purpose,
                                int sourceEpoch, String sourceRegion,
                                int targetEpoch, String targetRegion,
                                int requestedObjectCount, int migratedObjectCount, int delta,
                                List<String> recordKeys, String status, Instant committedAt) {
}
