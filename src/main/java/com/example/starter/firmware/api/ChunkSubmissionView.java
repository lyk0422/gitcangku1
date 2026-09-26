package com.example.starter.firmware.api;

/**
 * 分片接收结果视图。数量字段返回实际值与要求值，调用方无需猜测。
 *
 * @param taskId                 任务ID
 * @param attempt                当前尝试代次
 * @param status                 本次提交后的任务状态
 * @param acceptedCount          本次新接收的分片数（重复序号不重复接收）
 * @param receivedCount          当前代次累计已接收分片数
 * @param requiredCount          清单要求的分片总数
 * @param missingCount           距完整集合的缺口数（requiredCount - receivedCount）
 * @param result                 本次核验结果：INSTALLABLE / INTEGRITY_FAILED；未触发核验为 null
 * @param reason                 失败原因；成功或未核验为 null
 * @param detail                 失败细节（如不匹配序号的要求值与实际值）；无则为 null
 * @param computedPackageDigest  完整集合时计算的聚合摘要；集合不完整为 null
 * @param expectedPackageDigest  清单登记的完整包聚合摘要
 */
public record ChunkSubmissionView(long taskId, int attempt, String status,
                                  int acceptedCount, int receivedCount, int requiredCount, int missingCount,
                                  String result, String reason, String detail,
                                  String computedPackageDigest, String expectedPackageDigest) {
}
