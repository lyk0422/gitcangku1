package com.example.starter.evidence;

import java.time.LocalDateTime;

/**
 * 受监管交接检查点实体，对应 regulated_checkpoint 表。名单创建后不改不删。
 *
 * @param id           主键
 * @param transferKey  关联受监管交接业务键
 * @param checkpointNo 检查点号，自 1 起连续编号
 * @param deadlineAt   该点 UTC 截止时刻；到达时刻等于截止仍合法
 */
public record RegulatedCheckpoint(
        Long id,
        String transferKey,
        int checkpointNo,
        LocalDateTime deadlineAt) {
}