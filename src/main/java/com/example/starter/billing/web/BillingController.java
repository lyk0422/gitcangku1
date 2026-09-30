package com.example.starter.billing.web;

import com.example.starter.billing.service.BillingService;
import com.example.starter.billing.web.dto.AccountSummaryResponse;
import com.example.starter.billing.web.dto.CommitmentResponse;
import com.example.starter.billing.web.dto.CreateCommitmentRequest;
import com.example.starter.billing.web.dto.CreateOfferRequest;
import com.example.starter.billing.web.dto.OfferResponse;
import com.example.starter.billing.web.dto.ReadingResponse;
import com.example.starter.billing.web.dto.RegisterReadingRequest;
import com.example.starter.billing.web.dto.SettleRequest;
import com.example.starter.billing.web.dto.SettlementResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 削峰邀约业务链接口：邀约、承诺、计量、结算与查询。
 */
@RestController
@RequestMapping("/api/offers")
public class BillingController {

    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    /** 发布邀约。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfferResponse createOffer(@Valid @RequestBody CreateOfferRequest request) {
        return billingService.createOffer(request);
    }

    /** 用户承诺减量。 */
    @PostMapping("/{offerId}/commitments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommitmentResponse createCommitment(@PathVariable String offerId,
                                               @Valid @RequestBody CreateCommitmentRequest request) {
        return billingService.createCommitment(offerId, request);
    }

    /** 登记基线/实际计量读数（再次提交同种类即为更正）。 */
    @PostMapping("/{offerId}/users/{userId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse registerReading(@PathVariable String offerId,
                                           @PathVariable String userId,
                                           @Valid @RequestBody RegisterReadingRequest request) {
        return billingService.registerReading(offerId, userId, request);
    }

    /** 结算（首次或更正后重算差额）。 */
    @PostMapping("/{offerId}/users/{userId}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementResponse settle(@PathVariable String offerId,
                                     @PathVariable String userId,
                                     @Valid @RequestBody SettleRequest request) {
        return billingService.settle(offerId, userId, request);
    }

    /** 查询单条业务链汇总。 */
    @GetMapping("/{offerId}/users/{userId}/summary")
    public AccountSummaryResponse getSummary(@PathVariable String offerId,
                                             @PathVariable String userId) {
        return billingService.getSummary(offerId, userId);
    }
}
