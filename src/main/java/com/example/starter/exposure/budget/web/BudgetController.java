package com.example.starter.exposure.budget.web;

import com.example.starter.exposure.budget.BudgetService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多活动曝光预算闭环转移 API。
 */
@RestController
@RequestMapping("/api/budget")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /** 创建活动预算账本。 */
    @PostMapping("/campaigns")
    public ResponseEntity<BudgetCampaignResponse> createCampaign(
            @Valid @RequestBody CreateBudgetCampaignRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.createCampaign(request));
    }

    /** 按活动当前总预算申请曝光预占，归属在创建时冻结。 */
    @PostMapping("/reservations")
    public ResponseEntity<BudgetReservationResponse> apply(
            @Valid @RequestBody BudgetApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.apply(request));
    }

    /** 预占明细。 */
    @GetMapping("/reservations/{reservationId}")
    public BudgetReservationResponse getReservation(@PathVariable String reservationId) {
        return budgetService.getReservation(reservationId);
    }

    /** 确认预占：迟到回执始终归原活动。 */
    @PostMapping("/reservations/{reservationId}/confirm")
    public BudgetReservationResponse confirm(@PathVariable String reservationId,
                                             @Valid @RequestBody BudgetReservationActionRequest request) {
        return budgetService.confirm(reservationId, request);
    }

    /** 取消预占：仅 RESERVED 可取消并释放在途。 */
    @PostMapping("/reservations/{reservationId}/cancel")
    public BudgetReservationResponse cancel(@PathVariable String reservationId,
                                            @Valid @RequestBody BudgetReservationActionRequest request) {
        return budgetService.cancel(reservationId, request);
    }

    /** 转移预览：完整后态，不写数据。 */
    @PostMapping("/transfers/preview")
    public BudgetTransferPreviewResponse preview(@Valid @RequestBody BudgetTransferPreviewRequest request) {
        return budgetService.preview(request);
    }

    /** 激活转移：单事务整批更新并冻结证据。 */
    @PostMapping("/transfers/activate")
    public ResponseEntity<BudgetTransferActivateResponse> activate(
            @Valid @RequestBody BudgetTransferActivateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.activate(request));
    }

    /** 账本证据只读查询，稳定排序。 */
    @GetMapping("/transfers/{transferKey}/evidence")
    public TransferEvidenceResponse evidence(@PathVariable String transferKey) {
        return budgetService.evidence(transferKey);
    }
}
