package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.AttestRequest;
import com.example.starter.consent.dto.AttestRevokeRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.example.starter.consent.dto.RecipientStatusResponse;

import jakarta.validation.Valid;

/**
 * 接收方证明与批次查询 API：证明提交/续签、撤销、历史查询、接收方禁用与批次查询快照。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class AttestationController {

    private final AttestationService attestationService;
    private final QueryBatchService queryBatchService;

    public AttestationController(AttestationService attestationService, QueryBatchService queryBatchService) {
        this.attestationService = attestationService;
        this.queryBatchService = queryBatchService;
    }

    @PostMapping("/recipients/attestations")
    public AttestationResponse attest(@Valid @RequestBody AttestRequest request) {
        return attestationService.attest(request);
    }

    @PostMapping("/recipients/attestations/revocations")
    public AttestationResponse revokeAttestation(@Valid @RequestBody AttestRevokeRequest request) {
        return attestationService.revoke(request);
    }

    @GetMapping("/recipients/{recipientId}/attestations")
    public List<AttestationResponse> history(@PathVariable String recipientId,
                                             @RequestParam(required = false) Purpose purpose,
                                             @RequestParam(required = false) Integer epoch) {
        return attestationService.history(recipientId, purpose, epoch);
    }

    @PostMapping("/recipients/disables")
    public RecipientStatusResponse disable(@Valid @RequestBody RecipientDisableRequest request) {
        return attestationService.disable(request);
    }

    @PostMapping("/query-batches")
    public BatchQueryResponse createBatch(@Valid @RequestBody BatchQueryRequest request) {
        return queryBatchService.create(request);
    }

    @GetMapping("/query-batches/{batchId}")
    public BatchQueryResponse getBatch(@PathVariable long batchId) {
        return queryBatchService.get(batchId);
    }
}
