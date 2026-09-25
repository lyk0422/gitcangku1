package com.example.starter.consent;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.DelegateCreateRequest;
import com.example.starter.consent.dto.DelegateHistoryResponse;
import com.example.starter.consent.dto.DelegateQueryRequest;
import com.example.starter.consent.dto.DelegateQueryResponse;
import com.example.starter.consent.dto.DelegateQuerySnapshotResponse;
import com.example.starter.consent.dto.DelegateRenewRequest;
import com.example.starter.consent.dto.DelegateRenewResponse;
import com.example.starter.consent.dto.DelegateResponse;
import com.example.starter.consent.dto.DelegateRevokeRequest;
import com.example.starter.consent.dto.DelegateStatusResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 授权代理委托 API：委托创建、批量续签、撤销、委托历史、代理批量查询与批次快照。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class DelegateController {

    private final DelegateService delegateService;

    public DelegateController(DelegateService delegateService) {
        this.delegateService = delegateService;
    }

    @PostMapping("/delegates")
    public DelegateResponse create(@Valid @RequestBody DelegateCreateRequest request) {
        return delegateService.create(request);
    }

    @PostMapping("/delegates/renewals")
    public DelegateRenewResponse renew(@Valid @RequestBody DelegateRenewRequest request) {
        return delegateService.renew(request);
    }

    @PostMapping("/delegates/revocations")
    public DelegateStatusResponse revoke(@Valid @RequestBody DelegateRevokeRequest request) {
        return delegateService.revoke(request);
    }

    @GetMapping("/delegates/{delegateKey}")
    public DelegateHistoryResponse history(@PathVariable @NotBlank @Size(max = 128) String delegateKey) {
        return delegateService.history(delegateKey);
    }

    @PostMapping("/delegate-queries")
    public DelegateQueryResponse query(@Valid @RequestBody DelegateQueryRequest request) {
        return delegateService.query(request);
    }

    @GetMapping("/delegate-queries/{queryId}")
    public DelegateQuerySnapshotResponse snapshot(@PathVariable @NotBlank @Size(max = 128) String queryId) {
        return delegateService.snapshot(queryId);
    }
}
