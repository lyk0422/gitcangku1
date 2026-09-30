package com.example.starter.consent;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.dto.AttestationRequest;
import com.example.starter.consent.dto.AttestationResponse;
import com.example.starter.consent.dto.AttestationStatusView;
import com.example.starter.consent.dto.ReadAuditView;
import com.example.starter.consent.dto.RecipientReadResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 接收方承诺与依据承诺读取的 API，以及授权方读取审计查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class RecipientController {

    private final AttestationService attestationService;

    public RecipientController(AttestationService attestationService) {
        this.attestationService = attestationService;
    }

    /**
     * 接收方提交承诺：声明授权 epoch、用途与 UTC 到期时刻。
     */
    @PostMapping("/recipient/attestations")
    public AttestationResponse attest(@Valid @RequestBody AttestationRequest request) {
        return attestationService.attest(request);
    }

    /**
     * 接收方查询自身承诺状态。
     */
    @GetMapping("/recipient/attestations")
    public List<AttestationStatusView> statuses(@RequestParam @NotBlank String recipientKey) {
        return attestationService.listStatuses(recipientKey);
    }

    /**
     * 接收方依据承诺读取：四要素（接收方、用途、epoch、有效期）全部匹配才返回授权范围内数据。
     */
    @GetMapping("/recipient/records")
    public RecipientReadResponse read(@RequestParam @NotBlank String recipientKey,
                                      @RequestParam @NotBlank String subjectKey,
                                      @RequestParam @NotNull Purpose purpose,
                                      @RequestParam @NotNull @Min(1) Integer epoch,
                                      @RequestParam @NotBlank String recordKey) {
        return attestationService.read(recipientKey, subjectKey, purpose, epoch, recordKey);
    }

    /**
     * 授权方查询某主体的读取审计；撤回不删除历史读取记录。
     */
    @GetMapping("/consents/read-audits")
    public List<ReadAuditView> audits(@RequestParam @NotBlank String subjectKey) {
        return attestationService.listAudits(subjectKey);
    }
}
