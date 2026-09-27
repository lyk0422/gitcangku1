package com.example.starter.maintenance.domain;

import java.time.Instant;

/**
 * 隔离令确认记录：每条对应一名维护角色对全部锁定点的确认。
 *
 * @param id            行主键
 * @param isolationId   所属隔离令标识
 * @param operatorId    确认操作人
 * @param role          维护角色；同一隔离令内两名确认人角色不可相同
 * @param requestId     确认请求 requestId
 * @param seqNo         确认顺序号（1 第一名角色，2 第二名角色），用于稳定排序
 * @param confirmedAt   确认时刻（UTC）
 */
public record IsolationConfirmation(
        long id,
        long isolationId,
        String operatorId,
        String role,
        String requestId,
        int seqNo,
        Instant confirmedAt) {
}
