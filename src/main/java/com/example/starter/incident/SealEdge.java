package com.example.starter.incident;

/**
 * 封存包固化任务依赖边实体，对应 incident_log_seal_edges 表。
 * 有向边：本事件任务 taskKey → 阻塞事件 blockerIncidentKey；
 * seq 为包内按 (taskKey, blockerIncidentKey) 升序的序号。内容不可变。
 */
public record SealEdge(
        long id,
        long sealId,
        String taskKey,
        String blockerIncidentKey,
        int seq) {
}
