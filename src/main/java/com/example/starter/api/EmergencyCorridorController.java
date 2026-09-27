package com.example.starter.api;

import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetailView;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.EmergencyOccupancyRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReapplyHistoryView;
import com.example.starter.api.dto.RegularOccupancyRequest;
import com.example.starter.api.dto.YieldedReapplyRequest;
import com.example.starter.service.EmergencyCorridorService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空域应急走廊 API：走廊建立/撤销、常规/应急占用登记、起飞登记、
 * 让渡航线显式重新申请，以及走廊事件/让渡/重新申请历史的只读查询。
 */
@RestController
@RequestMapping("/api/airspace")
public class EmergencyCorridorController {

    private final EmergencyCorridorService service;

    public EmergencyCorridorController(EmergencyCorridorService service) {
        this.service = service;
    }

    /** 建立应急走廊（相交占用让渡或整次 422）。 */
    @PostMapping("/corridors")
    public ResponseEntity<MutationResponse> createCorridor(
            @Valid @RequestBody CorridorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCorridor(request));
    }

    /** 撤销应急走廊（只影响后续占用，已让渡不自动恢复）。 */
    @PostMapping("/corridors/revoke")
    public ResponseEntity<MutationResponse> revokeCorridor(
            @Valid @RequestBody CorridorRevokeRequest request) {
        return ResponseEntity.ok(service.revokeCorridor(request));
    }

    /** 查询走廊不可变事件与让渡历史。 */
    @GetMapping("/corridors/{corridorId}")
    public CorridorDetailView getCorridor(@PathVariable String corridorId) {
        return service.getCorridorDetail(corridorId);
    }

    /** 常规航线占用登记（事务内审查，命中有效走廊 409）。 */
    @PostMapping("/occupancies/regular")
    public ResponseEntity<MutationResponse> registerRegular(
            @Valid @RequestBody RegularOccupancyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerRegular(request));
    }

    /** 应急航线占用登记（必须命中指定有效走廊且通过禁飞审查）。 */
    @PostMapping("/occupancies/emergency")
    public ResponseEntity<MutationResponse> registerEmergency(
            @Valid @RequestBody EmergencyOccupancyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerEmergency(request));
    }

    /** 起飞登记（仅 ACTIVE 占用可登记）。 */
    @PostMapping("/occupancies/departure")
    public ResponseEntity<MutationResponse> registerDeparture(
            @Valid @RequestBody DepartureRequest request) {
        return ResponseEntity.ok(service.registerDeparture(request));
    }

    /** 查询占用详情。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyView getOccupancy(@PathVariable String occupancyId) {
        return service.getOccupancy(occupancyId);
    }

    /** 查询某航线全部占用（含已让渡/已起飞）。 */
    @GetMapping("/routes/{routeId}/occupancies")
    public List<OccupancyView> listOccupancies(@PathVariable String routeId) {
        return service.listOccupancies(routeId);
    }

    /** 已让渡航线显式重新申请（重新审查 + 新建占用，同事务提交）。 */
    @PostMapping("/occupancies/reapply")
    public ResponseEntity<MutationResponse> reapply(
            @Valid @RequestBody YieldedReapplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reapply(request));
    }

    /** 查询某原占用关联的全部重新申请历史。 */
    @GetMapping("/occupancies/{occupancyId}/reapplies")
    public List<ReapplyHistoryView> listReapplies(@PathVariable String occupancyId) {
        return service.listReapplies(occupancyId);
    }
}
