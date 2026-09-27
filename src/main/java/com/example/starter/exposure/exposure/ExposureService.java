package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.CampaignDiagnosticsResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptBatchRequest;
import com.example.starter.exposure.web.ReceiptBatchResponse;
import com.example.starter.exposure.web.ReceiptItemResult;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationDetailResponse;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateWindowRequest;
import com.example.starter.exposure.web.WindowVersionResponse;

import java.time.LocalDate;
import java.util.List;

/**
 * 公告曝光频控与归因窗口业务服务。
 */
public interface ExposureService {

    CampaignResponse createCampaign(CreateCampaignRequest request);

    /** 调整公告 UTC 归因窗口（追加不可变版本，历史归因不重算）。 */
    CampaignResponse updateWindow(String campaignId, UpdateWindowRequest request);

    ReservationResponse apply(ApplyExposureRequest request);

    ReservationResponse confirm(String reservationId, ReservationActionRequest request);

    ReservationResponse cancel(String reservationId, ReservationActionRequest request);

    ReservationResponse getReservation(String reservationId);

    QuotaResponse queryQuota(String campaignId, String visitorId, LocalDate utcDate);

    /**
     * 提交一批曝光确认回执：整批一次事务裁决。预占不存在/已取消/回执早于预占/
     * 归属不匹配/批内重复均返回 422 且整批不结算；窗口内 RESERVED 预占归因结算
     * （只结算一次），窗口外记录 LATE_RECEIPT，不二次确认、不释放配额。
     */
    ReceiptBatchResponse submitReceipts(ReceiptBatchRequest request);

    /** 只读：预占明细（快照窗口区间 + 回执历史），不触发过期结算、不改状态。 */
    ReservationDetailResponse getReservationDetail(String reservationId);

    /** 只读：预占回执历史，按受理时刻、流水编号稳定升序。 */
    List<ReceiptItemResult> listReceipts(String reservationId);

    /** 只读：公告归因窗口版本历史，按版本号升序。 */
    List<WindowVersionResponse> listWindowVersions(String campaignId);

    /** 只读：公告归因诊断（各状态/结果实际数量、实际归因比例与近期回执）。 */
    CampaignDiagnosticsResponse getCampaignDiagnostics(String campaignId, int recentLimit);
}
