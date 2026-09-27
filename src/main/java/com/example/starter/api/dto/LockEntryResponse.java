package com.example.starter.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 锁文件中单个名称对应的精确版本及其固化的校验证据。
 *
 * <p>checksum 与 attestations 为锁定时刻快照：后续见证撤销或变化不改写旧锁。
 *
 * @param attestations 固化的见证快照，按角色升序；每个版本锁定时须有两名不同角色
 */
public record LockEntryResponse(
        String name,
        int version,
        String checksum,
        List<FrozenAttestationView> attestations) {
}
