package com.example.starter.api;

import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDetail;
import com.example.starter.api.dto.CorridorDiagnostics;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupationCreateRequest;
import com.example.starter.api.dto.OccupationDetail;
import com.example.starter.api.dto.OccupationHistoryDto;
import com.example.starter.api.dto.ReapplyRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 空域应急走廊预留与占用裁决 API。查询端点均为只读，不改变状态。
 */
@RestController
@RequestMapping("/api/airspace")
public class EmergencyCorridorController {

    private final EmergencyCorridorService service;

    public EmergencyCorridorController(EmergencyCorridorService service) {
        this.service = service;
    }

    /** 创建应急走廊（相交可让渡常规占用同事务让渡；不可让渡/已起飞存在时整次 422）。 */
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

    /** 走廊明细查询。 */
    @GetMapping("/corridors/{corridorId}")
    public CorridorDetail getCorridor(@PathVariable String corridorId) {
        return service.getCorridor(corridorId);
    }

    /** 走廊让渡历史查询（不可变记录）。 */
    @GetMapping("/corridors/{corridorId}/yields")
    public List<YieldRecordDto> getCorridorYields(@PathVariable String corridorId) {
        return service.getCorridorYields(corridorId);
    }

    /** 创建空域占用（常规/应急；常规命中有效走廊 409 并给出走廊标识）。 */
    @PostMapping("/occupations")
    public ResponseEntity<MutationResponse> createOccupation(
            @Valid @RequestBody OccupationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupation(request));
    }

    /** 起飞登记（仅 ACTIVE 占用）。 */
    @PostMapping("/occupations/depart")
    public ResponseEntity<MutationResponse> depart(@Valid @RequestBody DepartureRequest request) {
        return ResponseEntity.ok(service.depart(request));
    }

    /** 已让渡占用重新申请（重新审查并生成新占用）。 */
    @PostMapping("/occupations/reapply")
    public ResponseEntity<MutationResponse> reapply(@Valid @RequestBody ReapplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reapply(request));
    }

    /** 占用明细查询（关联让渡记录与走廊版本）。 */
    @GetMapping("/occupations/{occupationId}")
    public OccupationDetail getOccupation(@PathVariable String occupationId) {
        return service.getOccupation(occupationId);
    }

    /** 占用历史查询（创建/让渡/起飞/重新申请事件）。 */
    @GetMapping("/occupations/{occupationId}/history")
    public OccupationHistoryDto getOccupationHistory(@PathVariable String occupationId) {
        return service.getOccupationHistory(occupationId);
    }

    /** 走廊时空诊断（只读）：给定航线、时段与高度范围，返回相交的有效走廊与常规占用。 */
    @GetMapping("/diagnostics/corridor-check")
    public CorridorDiagnostics diagnose(@RequestParam String routeId,
                                        @RequestParam long startUtc,
                                        @RequestParam long endUtc,
                                        @RequestParam int altMin,
                                        @RequestParam int altMax) {
        return service.diagnose(routeId, startUtc, endUtc, altMin, altMax);
    }
}
