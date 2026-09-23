package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.SettleWithdrawalRequest;
import com.example.starter.exposure.web.SnapshotItemResponse;
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

    /** 版本撤回：原子禁止新预占并冻结全部在途预占为 SETTLING 快照。 */
    WithdrawalResponse withdraw(WithdrawCampaignRequest request);

    /** 快照内预占回执：按截点/预占时刻/到期时刻规则决议 CONFIRMED 或 REJECTED。 */
    SnapshotItemResponse receipt(String reservationId, ReceiptRequest request);

    /** 发布方显式结算：提交快照完整预占版本集合；仍有可合法确认项时返回 409。 */
    WithdrawalResponse settle(String withdrawalKey, SettleWithdrawalRequest request);

    /** 撤回查询：返回截点、快照及逐项决议；只读，不触发任何状态变更。 */
    WithdrawalResponse getWithdrawal(String withdrawalKey);
}
