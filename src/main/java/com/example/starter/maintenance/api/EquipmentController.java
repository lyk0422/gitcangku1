package com.example.starter.maintenance.api;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.CorrectionCreateRequest;
import com.example.starter.maintenance.api.dto.CorrectionResponse;
import com.example.starter.maintenance.api.dto.CycleResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.EvidencePackageResponse;
import com.example.starter.maintenance.api.dto.EvidenceSubmitRequest;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.MaterialRegisterRequest;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.MaterialRevokeRequest;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.api.dto.TemplateRevisionRequest;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 设备工时保养判定与保养证据包 API。不连接真实设备，读数由调用方登记/补录。
 * 所有读取端点不改变状态；写操作按 requestId 幂等。
 */
@RestController
@RequestMapping("/api")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    /** 登记设备：保养周期（分钟）为正整数，登记后不可修改；逾期阈值可空，须严格大于周期。 */
    @PostMapping("/equipment")
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse register(@Valid @RequestBody RegisterEquipmentRequest req) {
        return service.register(req);
    }

    /** 新增工时读数（允许补录历史）；按采样时刻排序后累计分钟须单调不减，否则 422。 */
    @PostMapping("/equipment/{equipmentId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse addReading(@PathVariable String equipmentId,
                                      @Valid @RequestBody AddReadingRequest req) {
        return service.addReading(equipmentId, req);
    }

    /** 修订读数：只改累计分钟、不改采样时刻；作为历史保养锚点的读数返回 409。 */
    @PostMapping("/equipment/{equipmentId}/readings/{readingId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse reviseReading(@PathVariable String equipmentId,
                                         @PathVariable String readingId,
                                         @Valid @RequestBody ReviseReadingRequest req) {
        return service.reviseReading(equipmentId, readingId, req);
    }

    /** 修订保养模板：以完整检查项列表生成新版本，旧版本保留；证据确认时模板已再修订则 409。 */
    @PostMapping("/equipment/{equipmentId}/template/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse reviseTemplate(@PathVariable String equipmentId,
                                           @Valid @RequestBody TemplateRevisionRequest req) {
        return service.reviseTemplate(equipmentId, req);
    }

    /** 查看当前模板版本与检查项（按规范排序）。 */
    @GetMapping("/equipment/{equipmentId}/template")
    public TemplateResponse getTemplate(@PathVariable String equipmentId) {
        return service.getTemplate(equipmentId);
    }

    /** 提交证据包：整批检查结果与材料摘要先校验完整最终包，任一失败整次回滚，版本号递增。 */
    @PostMapping("/equipment/{equipmentId}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidencePackageResponse submitEvidence(@PathVariable String equipmentId,
                                                  @Valid @RequestBody EvidenceSubmitRequest req) {
        return service.submitEvidence(equipmentId, req);
    }

    /** 当前周期明细与完工门禁诊断（只读，不改变状态）。 */
    @GetMapping("/equipment/{equipmentId}/cycles/current")
    public CycleResponse getCurrentCycle(@PathVariable String equipmentId) {
        return service.getCurrentCycle(equipmentId);
    }

    /** 周期历史（含已完成周期固化快照）。 */
    @GetMapping("/equipment/{equipmentId}/cycles")
    public List<CycleResponse> listCycles(@PathVariable String equipmentId) {
        return service.listCycles(equipmentId);
    }

    /** 指定周期的证据包版本历史（按版本升序，字段、排序与 null 含义稳定）。 */
    @GetMapping("/equipment/{equipmentId}/cycles/{cycleNo}/evidence")
    public List<EvidencePackageResponse> listEvidence(@PathVariable String equipmentId,
                                                      @PathVariable int cycleNo) {
        return service.listEvidence(equipmentId, cycleNo);
    }

    /** 指定证据包版本明细（不可变快照）。 */
    @GetMapping("/equipment/{equipmentId}/cycles/{cycleNo}/evidence/{packageVersion}")
    public EvidencePackageResponse getEvidence(@PathVariable String equipmentId,
                                               @PathVariable int cycleNo,
                                               @PathVariable int packageVersion) {
        return service.getEvidence(equipmentId, cycleNo, packageVersion);
    }

    /** 登记材料批次。 */
    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse registerMaterial(@Valid @RequestBody MaterialRegisterRequest req) {
        return service.registerMaterial(req);
    }

    /** 撤销材料批次：已固化摘要不变，之后引用返回 422。 */
    @PostMapping("/materials/{materialId}/revocation")
    public MaterialResponse revokeMaterial(@PathVariable String materialId,
                                           @Valid @RequestBody MaterialRevokeRequest req) {
        return service.revokeMaterial(materialId, req);
    }

    /** 材料批次清单（含撤销状态）。 */
    @GetMapping("/materials")
    public List<MaterialResponse> listMaterials() {
        return service.listMaterials();
    }

    /**
     * 双人完工确认：另一维护角色确认当前证据包版本后原子完成保养并重置下一周期。
     * 证据未齐 / 存在 FAIL / 当前 OVERDUE 返回 422 并列明明细；版本漂移返回 409。
     */
    @PostMapping("/equipment/{equipmentId}/maintenances")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse completeMaintenance(@PathVariable String equipmentId,
                                                   @Valid @RequestBody CompleteMaintenanceRequest req) {
        return service.completeMaintenance(equipmentId, req);
    }

    /** 完工后更正：只追加附录，不能重开周期或覆盖工时历史。 */
    @PostMapping("/maintenances/{maintenanceId}/corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public CorrectionResponse addCorrection(@PathVariable long maintenanceId,
                                            @Valid @RequestBody CorrectionCreateRequest req) {
        return service.addCorrection(maintenanceId, req);
    }

    /** 更正附录历史；可按保养记录过滤。 */
    @GetMapping("/equipment/{equipmentId}/corrections")
    public List<CorrectionResponse> listCorrections(@PathVariable String equipmentId,
                                                    @RequestParam(name = "maintenanceId", required = false)
                                                    Long maintenanceId) {
        return service.listCorrections(equipmentId, maintenanceId);
    }

    /** 保养状态：本轮运行分钟达到周期为 DUE，达到逾期阈值为 OVERDUE。 */
    @GetMapping("/equipment/{equipmentId}/status")
    public StatusResponse getStatus(@PathVariable String equipmentId) {
        return service.getStatus(equipmentId);
    }

    /** 读数历史（当前值，按采样时刻升序）。 */
    @GetMapping("/equipment/{equipmentId}/readings")
    public List<ReadingResponse> listReadings(@PathVariable String equipmentId) {
        return service.listReadings(equipmentId);
    }

    /** 指定读数的修订历史。 */
    @GetMapping("/equipment/{equipmentId}/readings/{readingId}/revisions")
    public List<RevisionView> listRevisions(@PathVariable String equipmentId,
                                            @PathVariable String readingId) {
        return service.listRevisions(equipmentId, readingId);
    }

    /** 保养历史（锚点与证据版本快照，按锚点时间升序）。 */
    @GetMapping("/equipment/{equipmentId}/maintenances")
    public List<MaintenanceResponse> listMaintenances(@PathVariable String equipmentId) {
        return service.listMaintenances(equipmentId);
    }
}
