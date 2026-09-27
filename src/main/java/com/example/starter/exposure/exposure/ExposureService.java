package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ApplyExposureRequest;
import com.example.starter.exposure.web.AttributionDiagnosticsResponse;
import com.example.starter.exposure.web.BatchReceiptRequest;
import com.example.starter.exposure.web.BatchReceiptResponse;
import com.example.starter.exposure.web.CampaignResponse;
import com.example.starter.exposure.web.CreateCampaignRequest;
import com.example.starter.exposure.web.QuotaResponse;
import com.example.starter.exposure.web.ReceiptRequest;
import com.example.starter.exposure.web.ReceiptResponse;
import com.example.starter.exposure.web.ReservationActionRequest;
import com.example.starter.exposure.web.ReservationResponse;
import com.example.starter.exposure.web.UpdateWindowRequest;
import com.example.starter.exposure.web.WindowVersionResponse;

import java.time.LocalDate;
import java.util.List;

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

    /** 调整公告归因窗口：版本号 +1，历史版本与历史归因不重算。 */
    CampaignResponse updateWindow(String campaignId, UpdateWindowRequest request);

    /** 提交单笔曝光确认回执：窗口内计入归因并结算，窗口外记录 LATE_RECEIPT。 */
    ReceiptResponse submitReceipt(String reservationId, ReceiptRequest request);

    /** 批量提交回执：整批核对后一次事务提交，任一失败整批不结算。 */
    BatchReceiptResponse submitBatchReceipts(BatchReceiptRequest request);

    /** 回执明细（只读）。 */
    ReceiptResponse getReceipt(String receiptId);

    /** 某预占单的回执历史，按提交顺序升序（只读）。 */
    List<ReceiptResponse> listReceipts(String reservationId);

    /** 公告归因窗口版本历史，按版本号升序（只读）。 */
    List<WindowVersionResponse> listWindowHistory(String campaignId);

    /** 公告归因诊断统计（只读）；utcDate 为 null 时统计全部回执。 */
    AttributionDiagnosticsResponse attributionDiagnostics(String campaignId, LocalDate utcDate);
}
