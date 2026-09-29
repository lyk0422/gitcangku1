package com.example.starter.consent.ledger;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.consent.ledger.dto.DiagnosticResponse;
import com.example.starter.consent.ledger.dto.LedgerEntryResponse;
import com.example.starter.consent.ledger.dto.LedgerHistoryResponse;
import com.example.starter.consent.ledger.dto.QuotaAdjustRequest;
import com.example.starter.consent.ledger.dto.QuotaClearRequest;
import com.example.starter.consent.ledger.dto.QuotaResponse;
import com.example.starter.consent.ledger.dto.RestrictedQueryRequest;
import com.example.starter.consent.ledger.dto.RestrictedQueryResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 授权用途账本 API：额度调整/清除、受限批量查询、账本明细/历史与诊断。
 */
@Validated
@RestController
@RequestMapping("/api/v1/consents")
public class PurposeLedgerController {

    private final PurposeLedgerService service;

    public PurposeLedgerController(PurposeLedgerService service) {
        this.service = service;
    }

    /** 声明或调整指定主体＋用途＋代次的查询额度。 */
    @PostMapping("/quotas")
    public QuotaResponse adjustQuota(@Valid @RequestBody QuotaAdjustRequest request) {
        return service.adjustQuota(request);
    }

    /** 清除额度；已扣减账本记录保持不变，清除后任何受限查询按用途未声明拒绝。 */
    @PostMapping("/quotas/clear")
    public QuotaResponse clearQuota(@Valid @RequestBody QuotaClearRequest request) {
        return service.clearQuota(request);
    }

    /** 受限批量查询：先校验全部对象与剩余额度，通过后原子核销并固化账本。 */
    @PostMapping("/restricted-queries")
    public RestrictedQueryResponse restrictedQuery(@Valid @RequestBody RestrictedQueryRequest request) {
        return service.restrictedQuery(request);
    }

    /** 按序号查看账本明细；读取不改状态。 */
    @GetMapping("/ledger/{seq}")
    public LedgerEntryResponse detail(@PathVariable long seq) {
        return service.detail(seq);
    }

    /** 按主体（必选）、用途/代次（可选）、UTC 左闭右开时间区间查看账本历史。 */
    @GetMapping("/ledger")
    public LedgerHistoryResponse history(
            @RequestParam @NotBlank String subjectKey,
            @RequestParam(required = false) String purpose,
            @RequestParam(required = false) Integer epoch,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return service.history(subjectKey, purpose, epoch, from, to);
    }

    /** 诊断：授权与额度状态、余额、累计核销等；读取不改状态。 */
    @GetMapping("/diagnostics")
    public DiagnosticResponse diagnose(
            @RequestParam @NotBlank String subjectKey,
            @RequestParam @NotBlank String purpose,
            @RequestParam @NotNull Integer epoch) {
        return service.diagnose(subjectKey, purpose, epoch);
    }
}
