package com.example.starter.archival.domain;

import java.time.LocalDateTime;

/**
 * 档案页与脱酸批次的在组关系。同一批次内同一页只有一行；归还后该行置为 RELEASED，重新入组同一批次时复用。
 *
 * @param id           主键
 * @param batchId      批次ID
 * @param pageId       档案页ID
 * @param memberStatus 在组状态
 * @param joinedAt     最近一次入组时间（Asia/Shanghai）
 * @param releasedAt   归还时间（Asia/Shanghai）；在组中为 null
 * @param requestId    入组使用的 requestId
 */
public record BatchMember(Long id, Long batchId, Long pageId, MemberStatus memberStatus,
                          LocalDateTime joinedAt, LocalDateTime releasedAt, String requestId) {
}
