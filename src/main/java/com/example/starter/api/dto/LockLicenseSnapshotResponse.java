package com.example.starter.api.dto;

import java.util.List;

/**
 * 锁文件许可证快照：锁定成功时固化的策略版本与每个解析版本的许可证，
 * 不随后续许可证修订或策略修改而改变。
 *
 * @param policyVersion 锁定时生效的策略版本号；null 表示锁定时该命名空间未配置策略
 */
public record LockLicenseSnapshotResponse(
        long lockId,
        Long policyVersion,
        List<LockLicenseEntryResponse> entries) {
}
