package com.example.starter.api;

import com.example.starter.api.dto.CorridorConflictDiagnosticsDto;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetailDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyDetailDto;
import com.example.starter.api.dto.TakeoffRequest;
import com.example.starter.api.dto.YieldRecordDto;
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
 * 空域应急走廊预留与常规占用强制让渡 API。
 * 所有时间统一使用 UTC epoch 毫秒；时段与高度范围均按左闭右开解释。
 */
@RestController
@RequestMapping("/api/airspace")
public class EmergencyCorridorController {

    private final EmergencyCorridorService service;

    public EmergencyCorridorController(EmergencyCorridorService service) {
        this.service = service;
    }

    /** 创建应急走廊预留（同事务让渡相交的可让渡常规占用）。 */
    @PostMapping("/corridors")
    public ResponseEntity<MutationResponse> createCorridor(
            @Valid @RequestBody CorridorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCorridor(request));
    }

    /** 撤销应急走廊（只影响后续占用，已让渡航线不自动恢复）。 */
    @PostMapping("/corridors/revoke")
    public ResponseEntity<MutationResponse> revokeCorridor(
            @Valid @RequestBody CorridorRevokeRequest request) {
        return ResponseEntity.ok(service.revokeCorridor(request));
    }

    /** 创建空域占用（常规或应急；被让渡后的显式重新申请）。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupancy(request));
    }

    /** 起飞登记。 */
    @PostMapping("/occupancies/takeoff")
    public ResponseEntity<MutationResponse> registerTakeoff(
            @Valid @RequestBody TakeoffRequest request) {
        return ResponseEntity.ok(service.registerTakeoff(request));
    }

    /** 走廊明细查询（只读）。 */
    @GetMapping("/corridors/{corridorId}")
    public CorridorDetailDto getCorridor(@PathVariable String corridorId) {
        return service.getCorridor(corridorId);
    }

    /** 走廊让渡历史查询（只读，不可变记录）。 */
    @GetMapping("/corridors/{corridorId}/yields")
    public List<YieldRecordDto> getCorridorYields(@PathVariable String corridorId) {
        return service.getCorridorYields(corridorId);
    }

    /** 占用明细查询（只读，关联让渡记录）。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyDetailDto getOccupancy(@PathVariable String occupancyId) {
        return service.getOccupancy(occupancyId);
    }

    /** 航线占用历史查询（只读，含重新申请记录）。 */
    @GetMapping("/routes/{routeId}/occupancies")
    public List<OccupancyDetailDto> getRouteOccupancies(@PathVariable String routeId) {
        return service.getRouteOccupancies(routeId);
    }

    /** 一致性诊断：有效走廊与未让渡常规占用的时空冲突（只读，不变量要求为空）。 */
    @GetMapping("/diagnostics/corridor-conflicts")
    public CorridorConflictDiagnosticsDto diagnoseCorridorConflicts() {
        return service.diagnoseCorridorConflicts();
    }
}
