package com.example.starter.plan.model;

/**
 * 发布快照持久化记录。snapshotJson 固化发布时刻的编组、类别、
 * 区段限制版本与逐项校验结论，写入后不再修改。
 *
 * @param id           主键
 * @param planId       所属计划 id
 * @param scheduleKey  计划业务键
 * @param opType       触发发布的操作类型：PUBLISH / RESCHEDULE / BATCH_RESCHEDULE
 * @param conclusion   校验结论：PASSED
 * @param operator     操作者标识，未携带时为 null
 * @param snapshotJson 快照正文 JSON
 * @param createdAt    快照创建时刻，UTC 毫秒
 */
public record PublishSnapshot(long id, long planId, String scheduleKey, String opType,
                              String conclusion, String operator, String snapshotJson,
                              long createdAt) {
}
