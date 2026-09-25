package com.example.starter.consent.dto;

import com.example.starter.consent.Purpose;

/**
 * 保留权限只读查询响应：仅保留角色按记录标识查询时返回。
 *
 * <p>accessMode 固定为 LEGAL_HOLD，表示该数据仅用于法定保留目的；
 * consentAvailableForBusiness 固定为 false，不返回用于业务处理的授权可用状态。
 *
 * @param accessMode                   访问模式，固定 LEGAL_HOLD
 * @param consentAvailableForBusiness  授权对业务处理是否可用，固定 false
 * @param subjectKey                   主体标识
 * @param purpose                      用途
 * @param epoch                        记录所属授权代次
 * @param recordKey                    记录键
 * @param payload                      记录内容
 */
public record LegalHoldRecordResponse(
        String accessMode,
        boolean consentAvailableForBusiness,
        String subjectKey,
        Purpose purpose,
        int epoch,
        String recordKey,
        String payload) {

    public static LegalHoldRecordResponse of(String subjectKey, Purpose purpose, int epoch,
                                             String recordKey, String payload) {
        return new LegalHoldRecordResponse("LEGAL_HOLD", false, subjectKey, purpose, epoch, recordKey, payload);
    }
}
