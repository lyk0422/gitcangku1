package com.example.starter.exposure.budget;

import com.example.starter.exposure.budget.web.BudgetApplyRequest;
import com.example.starter.exposure.budget.web.BudgetCampaignResponse;
import com.example.starter.exposure.budget.web.BudgetReservationActionRequest;
import com.example.starter.exposure.budget.web.BudgetReservationResponse;
import com.example.starter.exposure.budget.web.BudgetTransferActivateRequest;
import com.example.starter.exposure.budget.web.BudgetTransferActivateResponse;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewRequest;
import com.example.starter.exposure.budget.web.BudgetTransferPreviewResponse;
import com.example.starter.exposure.budget.web.CreateBudgetCampaignRequest;
import com.example.starter.exposure.budget.web.TransferEvidenceResponse;

/**
 * 多活动曝光预算闭环转移业务服务。
 */
public interface BudgetService {

    /** 创建活动预算账本。 */
    BudgetCampaignResponse createCampaign(CreateBudgetCampaignRequest request);

    /** 按活动当前总预算申请曝光预占，归属在创建时冻结。 */
    BudgetReservationResponse apply(BudgetApplyRequest request);

    /** 确认预占：迟到回执始终归创建时冻结的原活动。 */
    BudgetReservationResponse confirm(String reservationId, BudgetReservationActionRequest request);

    /** 取消预占：仅 RESERVED 可取消并释放在途。 */
    BudgetReservationResponse cancel(String reservationId, BudgetReservationActionRequest request);

    /** 查询预占单。 */
    BudgetReservationResponse getReservation(String reservationId);

    /** 转移预览：按完整后态返回各活动总预算、已确认、在途、可转余额，不写数据。 */
    BudgetTransferPreviewResponse preview(BudgetTransferPreviewRequest request);

    /** 激活转移：单事务整批更新预算与版本并冻结明细与前后快照。 */
    BudgetTransferActivateResponse activate(BudgetTransferActivateRequest request);

    /** 账本证据只读查询，稳定排序。 */
    TransferEvidenceResponse evidence(String transferKey);
}
