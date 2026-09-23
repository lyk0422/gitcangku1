package com.example.starter.firmware.domain;

/**
 * 指令回执历史记录，结算回执与迟到旧代次回执均留存。
 *
 * @param id         回执历史ID
 * @param releaseId  所属投放活动ID
 * @param deviceId   设备ID
 * @param commandId  回执指向的指令ID
 * @param cohortId   回执结算（或本应结算）的队列ID
 * @param generation 回执携带的指令代次
 * @param result     回执结果：SUCCESS、FAILED 或 LATE（迁移提交后到达的旧代次回执）
 * @param settled    是否实际结算：true 计入队列统计并终结指令；false 为 LATE 存档
 * @param duplicate  是否为已结算指令的重复回执
 * @param receiptAt  回执到达时刻，UTC，ISO-8601
 */
public record ReceiptHistory(long id, long releaseId, String deviceId, long commandId, long cohortId,
                             int generation, String result, boolean settled, boolean duplicate,
                             String receiptAt) {
}
