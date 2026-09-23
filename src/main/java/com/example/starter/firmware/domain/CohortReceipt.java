package com.example.starter.firmware.domain;

/**
 * 队列回执入账记录，同设备同代次只入账一次，迟到回执留作证据。
 *
 * @param id           回执记录ID
 * @param campaignId   所属投放活动ID
 * @param deviceId     设备ID
 * @param cohortId     回执对应指令的队列ID
 * @param generation   回执代次
 * @param result       回执结果（SUCCESS/FAILED）
 * @param disposition  处置：SETTLED 按当前代次结算；LATE 迟到旧代次仅存档
 * @param receivedAtUtc 回执入账时刻，UTC，ISO-8601格式
 */
public record CohortReceipt(long id, long campaignId, String deviceId, long cohortId,
                            int generation, ReceiptResult result, ReceiptDisposition disposition,
                            String receivedAtUtc) {
}
