package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 标准器版本。每个版本至多由一个上级版本校准；血缘必须无环，子级有效窗口不得超出父级窗口。
 * 创建后血缘与窗口不可变，仅状态可经失效单激活由 VALID 变为 INVALID。
 *
 * @param id               标准器版本记录 ID（自增）
 * @param standardId       标准器版本业务键，全局唯一
 * @param parentStandardId 上级（校准方）标准器版本业务键；无上级为 null
 * @param validFrom        有效窗口起点（UTC，含）
 * @param validTo          有效窗口终点（UTC，不含）
 * @param certificateNo    上级出具的校准证书号
 * @param status           状态：VALID 有效 / INVALID 已失效
 * @param version          版本号，失效时递增，用于失效单 expectedVersion 校验
 * @param createdAt        创建时间（UTC）
 */
public record StandardVersion(
        long id,
        String standardId,
        String parentStandardId,
        Instant validFrom,
        Instant validTo,
        String certificateNo,
        StandardStatus status,
        int version,
        Instant createdAt) {
}
