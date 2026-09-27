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

import com.example.starter.maintenance.api.dto.AppendixView;
import com.example.starter.maintenance.api.dto.CreateAppendixRequest;
import com.example.starter.maintenance.api.dto.EvidenceResponse;
import com.example.starter.maintenance.api.dto.MaterialResponse;
import com.example.starter.maintenance.api.dto.RegisterMaterialRequest;
import com.example.starter.maintenance.api.dto.RevokeMaterialRequest;
import com.example.starter.maintenance.api.dto.SaveTemplateRequest;
import com.example.starter.maintenance.api.dto.SubmitEvidenceRequest;
import com.example.starter.maintenance.api.dto.TemplateResponse;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 保养模板、材料台账、证据包与更正附录 API。所有读取为纯查询，不改变状态。
 */
@RestController
@RequestMapping("/api")
public class MaintenanceEvidenceController {

    private final EquipmentService service;

    public MaintenanceEvidenceController(EquipmentService service) {
        this.service = service;
    }

    // ---------- 保养模板（版本化、不可变） ----------

    /** 创建新的模板版本；旧版本保留，周期证据固化其提交时的版本。 */
    @PostMapping("/equipment/{equipmentId}/template-versions")
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateResponse saveTemplate(@PathVariable String equipmentId,
                                         @Valid @RequestBody SaveTemplateRequest req) {
        return service.saveTemplate(equipmentId, req);
    }

    /** 查看设备当前（最新）模板版本。 */
    @GetMapping("/equipment/{equipmentId}/template-versions/latest")
    public TemplateResponse getLatestTemplate(@PathVariable String equipmentId) {
        return service.getLatestTemplate(equipmentId);
    }

    /** 查看指定模板版本（历史版本不被后续修订改写）。 */
    @GetMapping("/equipment/{equipmentId}/template-versions/{templateVersion}")
    public TemplateResponse getTemplate(@PathVariable String equipmentId,
                                        @PathVariable int templateVersion) {
        return service.getTemplate(equipmentId, templateVersion);
    }

    // ---------- 材料批次台账（全局） ----------

    /** 登记材料批次：编码格式 MAT- 开头加 4-16 位大写字母数字。 */
    @PostMapping("/materials")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse registerMaterial(@Valid @RequestBody RegisterMaterialRequest req) {
        return service.registerMaterial(req);
    }

    /** 撤销材料批次：撤销不可逆；撤销后新证据引用返回 422。 */
    @PostMapping("/materials/{batchCode}/revocations")
    @ResponseStatus(HttpStatus.CREATED)
    public MaterialResponse revokeMaterial(@PathVariable String batchCode,
                                           @Valid @RequestBody RevokeMaterialRequest req) {
        return service.revokeMaterial(batchCode, req);
    }

    /** 查看材料批次台账明细（含撤销状态、撤销时刻，null 字段语义稳定）。 */
    @GetMapping("/materials/{batchCode}")
    public MaterialResponse getMaterial(@PathVariable String batchCode) {
        return service.getMaterial(batchCode);
    }

    // ---------- 证据包 ----------

    /** 提交证据包新版本：一次更新多个检查项结果并追加材料摘要，按完整最终包裁决。 */
    @PostMapping("/equipment/{equipmentId}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public EvidenceResponse submitEvidence(@PathVariable String equipmentId,
                                           @Valid @RequestBody SubmitEvidenceRequest req) {
        return service.submitEvidence(equipmentId, req);
    }

    /** 查看当前在途周期的最新证据包（读取不改变状态）。 */
    @GetMapping("/equipment/{equipmentId}/evidence")
    public EvidenceResponse getCurrentEvidence(@PathVariable String equipmentId) {
        return service.getCurrentEvidence(equipmentId);
    }

    /** 查看指定周期的最新证据包。 */
    @GetMapping("/equipment/{equipmentId}/cycles/{cycleNo}/evidence")
    public EvidenceResponse getCycleEvidence(@PathVariable String equipmentId,
                                             @PathVariable int cycleNo) {
        return service.getCycleEvidence(equipmentId, cycleNo);
    }

    /** 查看指定周期指定证据包版本（历史不可变）。 */
    @GetMapping("/equipment/{equipmentId}/cycles/{cycleNo}/evidence/versions/{packageVersion}")
    public EvidenceResponse getEvidenceVersion(@PathVariable String equipmentId,
                                               @PathVariable int cycleNo,
                                               @PathVariable int packageVersion) {
        return service.getEvidenceVersion(equipmentId, cycleNo, packageVersion);
    }

    // ---------- 更正附录 ----------

    /** 对已完成周期创建更正附录；不能重开周期或覆盖工时历史。 */
    @PostMapping("/equipment/{equipmentId}/cycles/{cycleNo}/appendices")
    @ResponseStatus(HttpStatus.CREATED)
    public AppendixView createAppendix(@PathVariable String equipmentId,
                                       @PathVariable int cycleNo,
                                       @Valid @RequestBody CreateAppendixRequest req) {
        return service.createAppendix(equipmentId, cycleNo, req);
    }

    /** 查看指定周期的更正附录（按创建顺序）。 */
    @GetMapping("/equipment/{equipmentId}/cycles/{cycleNo}/appendices")
    public List<AppendixView> listAppendices(@PathVariable String equipmentId,
                                             @PathVariable int cycleNo) {
        return service.listAppendices(equipmentId, cycleNo);
    }
}
