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

import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.DriftCorrectionRequest;
import com.example.starter.maintenance.api.dto.DriftEvidenceResponse;
import com.example.starter.maintenance.api.dto.DriftPreviewResponse;
import com.example.starter.maintenance.api.dto.DriftActivateResponse;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 设备工时保养判定 API。不连接真实设备，读数由调用方登记/补录。
 */
@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    /** 登记设备：保养周期（分钟）为正整数，登记后不可修改；初始版本 1、累计工时 0。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse register(@Valid @RequestBody RegisterEquipmentRequest req) {
        return service.register(req);
    }

    /** 新增工时读数（允许补录历史）；按采样时刻排序后累计分钟须单调不减，否则 422。 */
    @PostMapping("/{equipmentId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse addReading(@PathVariable String equipmentId,
                                      @Valid @RequestBody AddReadingRequest req) {
        return service.addReading(equipmentId, req);
    }

    /** 修订读数：只改累计分钟、不改采样时刻；作为历史保养锚点的读数返回 409。 */
    @PostMapping("/{equipmentId}/readings/{readingId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse reviseReading(@PathVariable String equipmentId,
                                         @PathVariable String readingId,
                                         @Valid @RequestBody ReviseReadingRequest req) {
        return service.reviseReading(equipmentId, readingId, req);
    }

    /** 完成保养：以现存读数及其当前修订号为锚点，锚点时间须晚于上次保养锚点。 */
    @PostMapping("/{equipmentId}/maintenances")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse completeMaintenance(@PathVariable String equipmentId,
                                                   @Valid @RequestBody CompleteMaintenanceRequest req) {
        return service.completeMaintenance(equipmentId, req);
    }

    /** 保养状态：本轮运行分钟 = 最新读数 - 最近保养锚点工时（无保养从 0 计），达到周期即 DUE。 */
    @GetMapping("/{equipmentId}/status")
    public StatusResponse getStatus(@PathVariable String equipmentId) {
        return service.getStatus(equipmentId);
    }

    /** 读数历史（当前值，按采样时刻升序）。 */
    @GetMapping("/{equipmentId}/readings")
    public List<ReadingResponse> listReadings(@PathVariable String equipmentId) {
        return service.listReadings(equipmentId);
    }

    /** 指定读数的修订历史。 */
    @GetMapping("/{equipmentId}/readings/{readingId}/revisions")
    public List<RevisionView> listRevisions(@PathVariable String equipmentId,
                                            @PathVariable String readingId) {
        return service.listRevisions(equipmentId, readingId);
    }

    /** 保养历史（锚点快照，按锚点时间升序）。 */
    @GetMapping("/{equipmentId}/maintenances")
    public List<MaintenanceResponse> listMaintenances(@PathVariable String equipmentId) {
        return service.listMaintenances(equipmentId);
    }

    // ---------- 时钟漂移修正 ----------

    /** 预览：返回区间内全部读数的旧值、新值、插值段与受影响保养项目，不写数据。 */
    @PostMapping("/{equipmentId}/drift-corrections/preview")
    public DriftPreviewResponse previewCorrection(@PathVariable String equipmentId,
                                                  @Valid @RequestBody DriftCorrectionRequest req) {
        return service.previewCorrection(equipmentId, req);
    }

    /** 激活：一个事务内重读校验、生成读数新版本并整体重算保养项目，只产生一个快照版本。 */
    @PostMapping("/{equipmentId}/drift-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    public DriftActivateResponse activateCorrection(@PathVariable String equipmentId,
                                                    @Valid @RequestBody DriftCorrectionRequest req) {
        return service.activateCorrection(equipmentId, req);
    }

    /** 证据查询：只读，读数按采样时刻、读数标识稳定排序。 */
    @GetMapping("/{equipmentId}/drift-corrections/{correctionKey}")
    public DriftEvidenceResponse getCorrectionEvidence(@PathVariable String equipmentId,
                                                       @PathVariable String correctionKey) {
        return service.getCorrectionEvidence(equipmentId, correctionKey);
    }
}
