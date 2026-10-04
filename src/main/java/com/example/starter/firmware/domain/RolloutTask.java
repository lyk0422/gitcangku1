package com.example.starter.firmware.domain;

/**
 * 设备投放任务，同设备同发布单最多一条。
 *
 * @param id              任务ID
 * @param releaseId       所属发布单ID
 * @param deviceId        设备ID
 * @param status          任务状态
 * @param firstResult     首次回执结果（SUCCESS/FAILED），未回执为 null
 * @param baseDigest      拉取时冻结的基底摘要；增量任务必填，普通任务为 null
 * @param patchDigest     拉取时冻结的补丁摘要；增量任务必填，普通任务为 null
 * @param targetDigest    拉取时冻结的目标摘要；增量任务必填，普通任务为 null
 * @param imageGeneration 拉取时冻结的设备镜像代次；增量任务必填，普通任务为 null
 */
public record RolloutTask(long id, long releaseId, String deviceId, TaskStatus status,
                          ReceiptResult firstResult, String baseDigest, String patchDigest,
                          String targetDigest, Integer imageGeneration) {
}