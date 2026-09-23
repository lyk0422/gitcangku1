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

import com.example.starter.maintenance.api.dto.AddMaintenanceItemRequest;
import com.example.starter.maintenance.api.dto.AddReadingRequest;
import com.example.starter.maintenance.api.dto.CompleteMaintenanceRequest;
import com.example.starter.maintenance.api.dto.EquipmentResponse;
import com.example.starter.maintenance.api.dto.ItemStatusResponse;
import com.example.starter.maintenance.api.dto.ItemStatusSummaryResponse;
import com.example.starter.maintenance.api.dto.MaintenanceItemResponse;
import com.example.starter.maintenance.api.dto.MaintenanceResponse;
import com.example.starter.maintenance.api.dto.ReadingResponse;
import com.example.starter.maintenance.api.dto.RegisterEquipmentRequest;
import com.example.starter.maintenance.api.dto.ReviseReadingRequest;
import com.example.starter.maintenance.api.dto.RevisionView;
import com.example.starter.maintenance.api.dto.StatusResponse;
import com.example.starter.maintenance.service.EquipmentService;

/**
 * 设备工时保养判定 API。不连接真实设备，读数由调用方登记/补录。
 * 登记设备时迁移生成 DEFAULT 保养项目；旧接口继续操作 DEFAULT，新接口支持多保养项目。
 */
@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

    private final EquipmentService service;

    public EquipmentController(EquipmentService service) {
        this.service = service;
    }

    /** 登记设备：保养周期（分钟）为正整数，登记后不可修改；初始版本 1、累计工时 0，并迁移出 DEFAULT 项目。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EquipmentResponse register(@Valid @RequestBody RegisterEquipmentRequest req) {
        return service.register(req);
    }

    /** 新增保养项目：设备内 itemCode 唯一（DEFAULT 保留），最多再增 20 个；创建后不可修改或删除。 */
    @PostMapping("/{equipmentId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceItemResponse addItem(@PathVariable String equipmentId,
                                           @Valid @RequestBody AddMaintenanceItemRequest req) {
        return service.addItem(equipmentId, req);
    }

    /** 新增工时读数（允许补录历史）；按采样时刻排序后累计分钟须单调不减，否则 422。读数由全部项目共享。 */
    @PostMapping("/{equipmentId}/readings")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse addReading(@PathVariable String equipmentId,
                                      @Valid @RequestBody AddReadingRequest req) {
        return service.addReading(equipmentId, req);
    }

    /** 修订读数：只改累计分钟、不改采样时刻；被任一项目保养记录引用的读数返回 409 并列出全部 itemCode。 */
    @PostMapping("/{equipmentId}/readings/{readingId}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    public ReadingResponse reviseReading(@PathVariable String equipmentId,
                                         @PathVariable String readingId,
                                         @Valid @RequestBody ReviseReadingRequest req) {
        return service.reviseReading(equipmentId, readingId, req);
    }

    /** 完成保养：以现存读数及其当前修订号为指定项目锚点，锚点时间须晚于该项目上次锚点；不带 itemCode 时按 DEFAULT。 */
    @PostMapping("/{equipmentId}/maintenances")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenanceResponse completeMaintenance(@PathVariable String equipmentId,
                                                   @Valid @RequestBody CompleteMaintenanceRequest req) {
        return service.completeMaintenance(equipmentId, req);
    }

    /** DEFAULT 项目保养状态（旧接口兼容）：本轮运行分钟 = 最新读数 - DEFAULT 最近锚点工时（无保养从 0 计）。 */
    @GetMapping("/{equipmentId}/status")
    public StatusResponse getStatus(@PathVariable String equipmentId) {
        return service.getStatus(equipmentId);
    }

    /** 全项目状态汇总：返回按 itemCode 升序的项目列表，读数为全部项目共享的最新值。 */
    @GetMapping("/{equipmentId}/items/status")
    public ItemStatusSummaryResponse getItemStatusSummary(@PathVariable String equipmentId) {
        return service.getItemStatusSummary(equipmentId);
    }

    /** 单项目状态：独立锚点、运行分钟与周期判定。 */
    @GetMapping("/{equipmentId}/items/{itemCode}/status")
    public ItemStatusResponse getItemStatus(@PathVariable String equipmentId,
                                            @PathVariable String itemCode) {
        return service.getItemStatus(equipmentId, itemCode);
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

    /** DEFAULT 项目保养历史（旧接口兼容，锚点快照，按锚点时间升序）。 */
    @GetMapping("/{equipmentId}/maintenances")
    public List<MaintenanceResponse> listMaintenances(@PathVariable String equipmentId) {
        return service.listMaintenances(equipmentId);
    }

    /** 单项目保养历史（锚点快照，按锚点时间升序稳定排序）。 */
    @GetMapping("/{equipmentId}/items/{itemCode}/maintenances")
    public List<MaintenanceResponse> listItemMaintenances(@PathVariable String equipmentId,
                                                          @PathVariable String itemCode) {
        return service.listItemMaintenances(equipmentId, itemCode);
    }
}
