package com.example.starter.firmware.api;

import java.util.List;

/**
 * 分片接收提交后的完整性判定响应。
 *
 * @param task                任务最新视图
 * @param attempt             本次接收所属尝试代次
 * @param requiredCount       要求分片总数（登记数量）
 * @param receivedCount       本次提交后当前代次已接收的去重分片数（实际值）
 * @param missingCount        缺失数量 = requiredCount - receivedCount（差额，未收齐时为正）
 * @param missingSeqs         缺失分片序号列表（升序）；收齐或已失败时为当前集合相对登记清单的缺口
 * @param aggregateExpected   要求的聚合摘要（完整包摘要）
 * @param aggregateActual     实际聚合摘要；当前代次未收齐时为 null
 * @param integrityFailureCode 完整性失败原因代码；未失败为 null
 */
public record FragmentReceiptResponse(TaskView task, int attempt, int requiredCount, int receivedCount,
                                      int missingCount, List<Integer> missingSeqs,
                                      String aggregateExpected, String aggregateActual,
                                      String integrityFailureCode) {
}
