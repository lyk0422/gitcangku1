package com.example.starter.calibration.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 修订链只读结果：以测量业务键查询其所属修订链上原始测量与全部后继修订（按版本顺序）。
 *
 * @param rootKey 修订链根测量业务键
 * @param versions 版本链（自 1 起，按顺序）
 */
public record RevisionChainResponse(String rootKey, List<ChainVersion> versions) {

    /**
     * 修订链中的一个版本。
     *
     * @param measurementKey 该版本测量业务键
     * @param version        版本号（修订链自 1 起）
     * @param status         测量状态
     * @param reading        原始读数（十进制字符串）
     * @param computedValue  未舍入计算值（十进制字符串）
     * @param passed         是否合格
     * @param note           测量说明或修订原因；无说明为 null
     * @param createdAt      创建时间（UTC）
     */
    public record ChainVersion(
            String measurementKey,
            int version,
            String status,
            String reading,
            String computedValue,
            boolean passed,
            String note,
            Instant createdAt) {
    }
}
