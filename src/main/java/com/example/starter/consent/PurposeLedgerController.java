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

import com.example.starter.consent.dto.ClearRequest;
import com.example.starter.consent.dto.ClearResponse;
import com.example.starter.consent.dto.LedgerEntryResponse;
import com.example.starter.consent.dto.PurposeQueryRequest;
import com.example.starter.consent.dto.PurposeQueryResponse;
import com.example.starter.consent.dto.Queries;
import com.example.starter.consent.dto.QuotaAdjustRequest;
import com.example.starter.consent.dto.QuotaResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * 授权用途账本 API：用途额度声明/调整、受限批量查询（核销记账）、撤回后清除，
 * 以及账本明细、历史区间与诊断的只读查询。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class PurposeLedgerController {

    private final PurposeLedgerService purposeLedgerService;

    public PurposeLedgerController(PurposeLedgerService purposeLedgerService) {
        this.purposeLedgerService = purposeLedgerService;
    }

    /** 声明或调整某 epoch 某用途的查询总额度。 */
    @PostMapping("/purpose-quotas")
    public QuotaResponse adjustQuota(@Valid @RequestBody QuotaAdjustRequest request) {
        return purposeLedgerService.adjustQuota(request);
    }

    /** 查看某 epoch 某用途的当前额度、已用与剩余余额（只读）。 */
    @GetMapping("/purpose-quotas")
    public QuotaResponse getQuota(@RequestParam @NotBlank String subjectKey,
                                  @RequestParam Purpose purpose,
                                  @RequestParam @Min(1) int epoch) {
        return purposeLedgerService.getQuota(subjectKey, purpose, epoch);
    }

    /** 受限批量查询：按对象数核销余额并追加账本；任一对象或额度校验失败则整体拒绝。 */
    @PostMapping("/purpose-queries")
    public PurposeQueryResponse query(@Valid @RequestBody PurposeQueryRequest request) {
        return purposeLedgerService.query(request);
    }

    /** 撤回后的合规清除：仅标记清除时间，不删除数据与账本。 */
    @PostMapping("/consents/clearing")
    public ClearResponse clear(@Valid @RequestBody ClearRequest request) {
        return purposeLedgerService.clear(request);
    }

    /** 查看某 epoch 某用途的账本明细，按全局顺序号稳定升序（只读）。 */
    @GetMapping("/purpose-ledger")
    public List<LedgerEntryResponse> listEntries(@RequestParam @NotBlank String subjectKey,
                                                 @RequestParam Purpose purpose,
                                                 @RequestParam @Min(1) int epoch) {
        return purposeLedgerService.listEntries(subjectKey, purpose, epoch);
    }

    /** 按顺序号查看单条历史证据（只读）。 */
    @GetMapping("/purpose-ledger/{ledgerSeq}")
    public LedgerEntryResponse getEntry(@PathVariable @Min(1) long ledgerSeq) {
        return purposeLedgerService.getEntry(ledgerSeq);
    }

    /** 历史区间查询：UTC 左闭右开 [from, to)，按 (发生时间, 顺序号) 稳定升序（只读）。 */
    @GetMapping("/purpose-ledger/history")
    public Queries.HistoryWindow history(@RequestParam @NotBlank String subjectKey,
                                         @RequestParam Purpose purpose,
                                         @RequestParam(required = false) Integer epoch,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to) {
        return purposeLedgerService.history(subjectKey, purpose, epoch, from, to);
    }

    /** 诊断视图：各 epoch 授权状态、额度、记录清除数量与账本条数（只读）。 */
    @GetMapping("/purpose-diagnostics")
    public Queries.Diagnostics diagnostics(@RequestParam @NotBlank String subjectKey,
                                           @RequestParam Purpose purpose) {
        return purposeLedgerService.diagnostics(subjectKey, purpose);
    }
}
