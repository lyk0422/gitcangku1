package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.WithdrawCampaignRequest;
import com.example.starter.exposure.web.WithdrawalResponse;

import java.time.LocalDate;

/**
 * 公告曝光频控业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /**
     * 撤回一个公告版本：原子禁止该版本新预占，并把全部 PENDING 预占冻结为 SETTLING 快照。
     */
    WithdrawalResponse withdraw(WithdrawCampaignRequest request);

    /**
     * 提交快照内预占的回执：满足截点/预占时刻/有效期规则则确认，否则释放为 REJECTED。
     */
    ReceiptResponse submitReceipt(ReceiptRequest request);

    /**
     * 发布方显式结算：提交快照完整预占版本集合；仍有可合法确认项时返回 409 且不产生任何变更。
     */
    WithdrawalResponse settle(String withdrawalKey, SettleWithdrawalRequest request);

    /**
     * 只读查询撤回单：截点、快照及逐项决议；不触发任何状态变更。
     */
    WithdrawalResponse getWithdrawal(String withdrawalKey);
}
