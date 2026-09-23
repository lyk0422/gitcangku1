package com.example.starter.exposure.web;

import com.example.starter.exposure.exposure.BudgetService;
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
 * 活动预算账本与预算转移 API。
 */
@RestController
@RequestMapping("/api/exposure/budget")
public class BudgetController {

    private final BudgetService budgetService;

    public BudgetController(BudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /** 创建活动预算账本（左闭右开 UTC 投放窗口内的非负整数预算）。 */
    @PostMapping("/accounts")
    public ResponseEntity<BudgetAccountResponse> createAccount(
            @Valid @RequestBody CreateBudgetAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.createAccount(request));
    }

    /** 活动预算账本明细：总预算、在途预占、已确认与可转余额。 */
    @GetMapping("/accounts/{campaignId}")
    public BudgetAccountResponse getAccount(@PathVariable String campaignId) {
        return budgetService.getAccount(campaignId);
    }

    /** 预算转移预览：按完整后态返回各活动账本状态，不写数据。 */
    @PostMapping("/transfers/preview")
    public TransferPreviewResponse preview(@Valid @RequestBody PreviewTransferRequest request) {
        return budgetService.preview(request);
    }

    /** 激活预算转移：一个事务内整体生效或整体回滚，冻结明细与前后快照。 */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> activate(
            @Valid @RequestBody ActivateTransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(budgetService.activate(request));
    }

    /** 账本证据查询：只读，规范化明细与前后快照稳定排序。 */
    @GetMapping("/transfers/{transferKey}")
    public TransferResponse getTransfer(@PathVariable String transferKey) {
        return budgetService.getTransfer(transferKey);
    }
}
