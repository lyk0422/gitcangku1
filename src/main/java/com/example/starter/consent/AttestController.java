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

import com.example.starter.consent.dto.AttestRevokeRequest;
import com.example.starter.consent.dto.AttestSubmitRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.BatchDetailResponse;
import com.example.starter.consent.dto.BatchQueryRequest;
import com.example.starter.consent.dto.BatchQueryResponse;
import com.example.starter.consent.dto.RecipientDisableRequest;
import com.example.starter.consent.dto.RecipientRegisterRequest;
import com.example.starter.consent.dto.RecipientResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 接收方证明与批量查询 API：
 * 接收方登记/禁用、证明提交/撤销/历史、带证明门禁的批量数据查询与批次详情。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class AttestController {

    private final AttestService attestService;

    public AttestController(AttestService attestService) {
        this.attestService = attestService;
    }

    @PostMapping("/recipients")
    public RecipientResponse register(@Valid @RequestBody RecipientRegisterRequest request) {
        return attestService.registerRecipient(request);
    }

    @PostMapping("/recipients/disable")
    public RecipientResponse disable(@Valid @RequestBody RecipientDisableRequest request) {
        return attestService.disableRecipient(request);
    }

    @PostMapping("/attestations")
    public AttestationResponse submit(@Valid @RequestBody AttestSubmitRequest request) {
        return attestService.submitAttestation(request);
    }

    @PostMapping("/attestations/revocations")
    public AttestationResponse revoke(@Valid @RequestBody AttestRevokeRequest request) {
        return attestService.revokeAttestation(request);
    }

    @GetMapping("/attestations")
    public List<AttestationResponse> history(@RequestParam @NotBlank String recipientId,
                                             @RequestParam Purpose purpose,
                                             @RequestParam @Min(1) int epoch) {
        return attestService.attestationHistory(recipientId, purpose, epoch);
    }

    @PostMapping("/batch-queries")
    public BatchQueryResponse batchQuery(@Valid @RequestBody BatchQueryRequest request) {
        return attestService.batchQuery(request);
    }

    @GetMapping("/batch-queries/{batchId}")
    public BatchDetailResponse getBatch(@PathVariable @NotBlank String batchId) {
        return attestService.getBatch(batchId);
    }
}
