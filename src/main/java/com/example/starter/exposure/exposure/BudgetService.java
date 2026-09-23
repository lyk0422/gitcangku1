package com.example.starter.exposure.exposure;

import com.example.starter.exposure.web.ActivateTransferRequest;
import com.example.starter.exposure.web.BudgetAccountResponse;
import com.example.starter.exposure.web.CreateBudgetAccountRequest;
import com.example.starter.exposure.web.PreviewTransferRequest;
import com.example.starter.exposure.web.TransferPreviewResponse;
import com.example.starter.exposure.web.TransferResponse;

/**
 * 活动预算账本与预算转移业务服务。
 */
public interface BudgetService {

    BudgetAccountResponse createAccount(CreateBudgetAccountRequest request);

    BudgetAccountResponse getAccount(String campaignId);

    TransferPreviewResponse preview(PreviewTransferRequest request);

    TransferResponse activate(ActivateTransferRequest request);

    TransferResponse getTransfer(String transferKey);
}
