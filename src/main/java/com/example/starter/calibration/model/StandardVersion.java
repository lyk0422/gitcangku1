package com.example.starter.calibration.model;

import java.time.Instant;

/**
 * 标准器版本（血缘节点）。每个版本可由一个上级标准器版本校准；
 * 血缘必须无环，子级有效窗口不得超出父级窗口。有效窗口为 UTC 左闭右开 [validFrom, validTo)。
 *
 * @param id              标准器版本 ID（自增）
 * @param versionKey      版本业务键，全局唯一
 * @param standardId      所属标准器业务 ID
 * @param parentVersionId 上级标准器版本 ID；null 表示血缘根版本
 * @param validFrom       有效期起点（UTC，含）
 * @param validTo         有效期终点（UTC，不含）
 * @param certificateNo   校准证书号
 * @param status          状态：VALID 有效 / INVALID 已失效
 * @param createdAt       创建时间（UTC）
 */
public record StandardVersion(
        long id,
        String versionKey,
        String standardId,
        Long parentVersionId,
        Instant validFrom,
        Instant validTo,
        String certificateNo,
        StandardVersionStatus status,
        Instant createdAt) {
}
