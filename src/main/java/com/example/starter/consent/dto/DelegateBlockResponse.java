package com.example.starter.consent.dto;

import java.util.List;

import com.example.starter.consent.Purpose;
import com.example.starter.consent.RejectionReason;

/**
 * 批量查询阻断审计响应：一次被整批拒绝的查询及其逐主体原因。
 *
 * @param id          阻断记录标识
 * @param requestId   失败请求的 requestId（不占用幂等键）
 * @param delegateId  代理人标识
 * @param purpose     查询用途
 * @param subjectKeys 请求的主体集合
 * @param reasons     逐主体阻断原因
 */
public record DelegateBlockResponse(
        long id,
        String requestId,
        String delegateId,
        Purpose purpose,
        List<String> subjectKeys,
        List<RejectionReason> reasons) {
}
