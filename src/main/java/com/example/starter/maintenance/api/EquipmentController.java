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
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.api.dto.WitnessReadingRequest;
import com.example.starter.maintenance.api.dto.WitnessResponse;
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

    /** 登记设备：保养周期与大幅修订见证阈值（分钟）均为正整数，登记后不可修改；初始版本 1、累计工时 0。 */
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

    /**
     * 修订读数：只改累计分钟、不改采样时刻；作为历史保养锚点的读数返回 409。
     * 新值与当前有效读数差额严格大于设备见证阈值时先进入待确认（响应中带 pending* 字段），
     * 须由另一名维护人调用见证接口后才生效。
     */
    @PostMapping("/{equipmentId}/readings/{readingId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse reviseReading(@PathVariable String equipmentId,
                                         @PathVariable String readingId,
                                         @Valid @RequestBody ReviseReadingRequest req) {
        return service.reviseReading(equipmentId, readingId, req);
    }

    /**
     * 见证待确认的大幅修订：见证人必须不同于提交人，且确认的修订号/读数与申请一致，
     * 读数版本已变化（出现新的有效修订）时返回 422，旧见证不会批准新读数。
     */
    @PostMapping("/{equipmentId}/readings/{readingId}/witness")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse witnessReading(@PathVariable String equipmentId,
                                          @PathVariable String readingId,
                                          @Valid @RequestBody WitnessReadingRequest req) {
        return service.witnessReading(equipmentId, readingId, req);
    }

    /** 完成保养：以现存有效读数及其当前修订号为锚点，锚点时间须晚于上次保养锚点；待确认读数不得放行。 */
    @PostMapping("/{equipmentId}/maintenances")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse completeMaintenance(@PathVariable String equipmentId,
                                                   @Valid @RequestBody CompleteMaintenanceRequest req) {
        return service.completeMaintenance(equipmentId, req);
    }

    /** 保养状态：本轮运行分钟 = 最新有效读数 - 最近保养锚点工时（无保养从 0 计），达到周期即 DUE。 */
    @GetMapping("/{equipmentId}/status")
    public StatusResponse getStatus(@PathVariable String equipmentId) {
        return service.getStatus(equipmentId);
    }

    /** 读数历史（有效读数按采样时刻升序；有待确认修订时同时展示待确认值、差额、阈值与提交人）。 */
    @GetMapping("/{equipmentId}/readings")
    public List<ReadingResponse> listReadings(@PathVariable String equipmentId) {
        return service.listReadings(equipmentId);
    }

    /** 指定读数的修订历史（含大幅修订的阈值/差额/提交人/见证人快照）。 */
    @GetMapping("/{equipmentId}/readings/{readingId}/revisions")
    public List<RevisionView> listRevisions(@PathVariable String equipmentId,
                                            @PathVariable String readingId) {
        return service.listRevisions(equipmentId, readingId);
    }

    /** 设备下全部待确认的大幅修订申请。 */
    @GetMapping("/{equipmentId}/witnesses")
    public List<WitnessResponse> listWitnesses(@PathVariable String equipmentId) {
        return service.listWitnesses(equipmentId);
    }

    /** 指定读数的待确认大幅修订（无则 404）。 */
    @GetMapping("/{equipmentId}/readings/{readingId}/witness")
    public WitnessResponse getWitness(@PathVariable String equipmentId,
                                      @PathVariable String readingId) {
        return service.getWitness(equipmentId, readingId);
    }

    /** 保养历史（锚点快照，按锚点时间升序）。 */
    @GetMapping("/{equipmentId}/maintenances")
    public List<MaintenanceResponse> listMaintenances(@PathVariable String equipmentId) {
        return service.listMaintenances(equipmentId);
    }
}
