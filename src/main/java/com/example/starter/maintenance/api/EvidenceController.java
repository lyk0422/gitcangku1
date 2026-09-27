package com.example.starter.maintenance.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.AppendixResponse;
import com.example.starter.maintenance.api.dto.ConfirmCompletionRequest;
import com.example.starter.maintenance.api.dto.ConfirmationResponse;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.DiagnosticsResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageSummary;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.service.EvidenceService;

/**
 * 保养证据包 API：证据提交（递增版本）、双人完工确认、更正附录与明细/历史/诊断查询。
 * 所有查询只读，不改变状态。
 */
@RestController
@RequestMapping("/api/equipment/{equipmentId}")
public class EvidenceController {

    private final EvidenceService service;

    public EvidenceController(EvidenceService service) {
        this.service = service;
    }

    /** 提交证据包：整体替换当前周期证据并生成递增版本；先校验完整最终包，任一失败整次回滚。 */
    @PostMapping("/evidence-packages")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidencePackageResponse submitEvidence(@PathVariable String equipmentId,
                                                  @Valid @RequestBody SubmitEvidenceRequest req) {
        return service.submitEvidence(equipmentId, req);
    }

    /** 双人完工确认：确认人不得为提交人；确认当前证据包版本后原子完成保养并重置下一周期。 */
    @PostMapping("/completion-confirmations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConfirmationResponse confirmCompletion(@PathVariable String equipmentId,
                                                  @Valid @RequestBody ConfirmCompletionRequest req) {
        return service.confirmCompletion(equipmentId, req);
    }

    /** 当前周期最新证据包明细。 */
    @GetMapping("/evidence-packages/current")
    public EvidencePackageResponse getCurrentEvidence(@PathVariable String equipmentId) {
        return service.getEvidencePackage(equipmentId, null, null);
    }

    /** 指定周期与版本的证据包明细（历史周期证据保持提交时快照，不被后续模板修订改写）。 */
    @GetMapping("/evidence-packages/{cycleNo}/{packageVersion}")
    public EvidencePackageResponse getEvidencePackage(@PathVariable String equipmentId,
                                                      @PathVariable long cycleNo,
                                                      @PathVariable int packageVersion) {
        return service.getEvidencePackage(equipmentId, cycleNo, packageVersion);
    }

    /** 证据包历史（全部周期、全部版本，周期/版本升序）。 */
    @GetMapping("/evidence-packages")
    public List<EvidencePackageSummary> listEvidenceHistory(@PathVariable String equipmentId) {
        return service.listEvidenceHistory(equipmentId);
    }

    /** 当前周期证据诊断：缺失必检项、FAIL 项、已撤销材料与数量实际值/要求值。 */
    @GetMapping("/evidence-diagnostics")
    public DiagnosticsResponse getDiagnostics(@PathVariable String equipmentId) {
        return service.getDiagnostics(equipmentId);
    }

    /** 创建更正附录：完工后发现错误只能追加附录，不重开周期、不改写历史。 */
    @PostMapping("/maintenances/{maintenanceId}/appendices")
    @ResponseStatus(HttpStatus.CREATED)
    public AppendixResponse createAppendix(@PathVariable String equipmentId,
                                           @PathVariable long maintenanceId,
                                           @Valid @RequestBody CreateAppendixRequest req) {
        return service.createAppendix(equipmentId, maintenanceId, req);
    }

    /** 保养记录的更正附录列表（创建顺序升序）。 */
    @GetMapping("/maintenances/{maintenanceId}/appendices")
    public List<AppendixResponse> listAppendices(@PathVariable String equipmentId,
                                                 @PathVariable long maintenanceId) {
        return service.listAppendices(equipmentId, maintenanceId);
    }
}
