package com.example.starter.firmware.domain;

/**
 * 完整性判定事件，只增不改。固化判定时刻的发布版本、要求/实际/缺失数量、
 * 缺失/重复/不匹配分片序号、聚合摘要快照与UTC时刻。
 *
 * @param id                 事件ID
 * @param taskId             任务尝试ID
 * @param attemptNo          尝试代次
 * @param releaseId          发布单ID快照
 * @param releaseVersion     判定时刻发布单版本号快照
 * @param result             判定结果：INSTALLABLE / INTEGRITY_FAILED
 * @param reason             失败原因码；INSTALLABLE 时为 OK
 * @param requiredShardCount 要求分片总数
 * @param receivedShardCount 判定时实际接收分片数（按序号去重）
 * @param missingShardCount  缺失分片数
 * @param missingShards      缺失分片序号，逗号分隔升序，无缺失为空串
 * @param duplicateShards    重复提交的分片序号，逗号分隔升序，无重复为空串
 * @param mismatchedShards   摘要不匹配的分片序号，逗号分隔升序，无不匹配为空串
 * @param expectedFullDigest 登记的完整包聚合摘要快照
 * @param actualFullDigest   按接收分片聚合的实际摘要；集合不完整时为 null
 * @param decidedAtUtc       判定时刻，UTC，ISO-8601格式
 */
public record IntegrityEvent(long id, long taskId, int attemptNo, long releaseId, int releaseVersion,
                             String result, String reason, int requiredShardCount, int receivedShardCount,
                             int missingShardCount, String missingShards, String duplicateShards,
                             String mismatchedShards, String expectedFullDigest, String actualFullDigest,
                             String decidedAtUtc) {
}
