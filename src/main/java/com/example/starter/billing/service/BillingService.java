package com.example.starter.billing.service;

import com.example.starter.billing.web.dto.AccountSummaryResponse;
import com.example.starter.billing.web.dto.CommitmentResponse;
import com.example.starter.billing.web.dto.CreateCommitmentRequest;
import com.example.starter.billing.web.dto.CreateOfferRequest;
import com.example.starter.billing.web.dto.OfferResponse;
import com.example.starter.billing.web.dto.ReadingResponse;
import com.example.starter.billing.web.dto.RegisterReadingRequest;
import com.example.starter.billing.web.dto.SettleRequest;
import com.example.starter.billing.web.dto.SettlementResponse;

/**
 * 削峰邀约业务链：邀约发布、用户承诺、计量确认（含更正）与结算（含差额）。
 */
public interface BillingService {

    /**
     * 调度方发布邀约。
     *
     * @param request 邀约请求（UTC 左闭右开时段）
     * @return 邀约视图
     */
    OfferResponse createOffer(CreateOfferRequest request);

    /**
     * 用户对邀约作出减量承诺；每用户每邀约至多一个有效承诺，requestId 保证幂等。
     *
     * @param offerId 邀约 ID
     * @param request 承诺请求
     * @return 承诺视图
     */
    CommitmentResponse createCommitment(String offerId, CreateCommitmentRequest request);

    /**
     * 登记基线/实际读数；再次提交同种类读数视为更正，生成新版本校正记录（不得覆盖原读数/结算）。
     *
     * @param offerId 邀约 ID
     * @param userId  用户 ID
     * @param request 读数请求
     * @return 读数视图
     */
    ReadingResponse registerReading(String offerId, String userId, RegisterReadingRequest request);

    /**
     * 结算：时段结束后按同一已提交版本的基线、实际读数固化一笔账目；读数问题返回 422，时段未结束返回 409。
     *
     * @param offerId 邀约 ID
     * @param userId  用户 ID
     * @param request 结算请求（幂等键）
     * @return 结算视图
     */
    SettlementResponse settle(String offerId, String userId, SettleRequest request);

    /**
     * 查询单条业务链：承诺、读数版本、奖励电量、净结算额与全部账目。
     *
     * @param offerId 邀约 ID
     * @param userId  用户 ID
     * @return 汇总视图
     */
    AccountSummaryResponse getSummary(String offerId, String userId);
}
