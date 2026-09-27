package com.example.starter.api;

import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.CorridorDto;
import com.example.starter.api.dto.CorridorRevokeRequest;
import com.example.starter.api.dto.DepartureRegisterRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyEventDto;
import com.example.starter.api.dto.OccupancyReapplyRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.YieldRecordDto;
import com.example.starter.service.CorridorService;
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
 * 空域应急走廊与占用 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class CorridorController {

    private final CorridorService service;

    public CorridorController(CorridorService service) {
        this.service = service;
    }

    /** 建立应急走廊（授权调度员）。 */
    @PostMapping("/corridors")
    public ResponseEntity<MutationResponse> createCorridor(
            @Valid @RequestBody CorridorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCorridor(request));
    }

    /** 撤销应急走廊。 */
    @PostMapping("/corridors/revoke")
    public ResponseEntity<MutationResponse> revokeCorridor(
            @Valid @RequestBody CorridorRevokeRequest request) {
        return ResponseEntity.ok(service.revokeCorridor(request));
    }

    /** 登记空域占用（常规或应急）。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createOccupancy(request));
    }

    /** 已让渡占用重新审查后的显式重新申请。 */
    @PostMapping("/occupancies/reapply")
    public ResponseEntity<MutationResponse> reapplyOccupancy(
            @Valid @RequestBody OccupancyReapplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reapplyOccupancy(request));
    }

    /** 起飞登记。 */
    @PostMapping("/departures")
    public ResponseEntity<MutationResponse> registerDeparture(
            @Valid @RequestBody DepartureRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerDeparture(request));
    }

    /** 查询单个走廊。 */
    @GetMapping("/corridors/{corridorId}")
    public CorridorDto getCorridor(@PathVariable String corridorId) {
        return service.getCorridor(corridorId);
    }

    /** 列出全部走廊（不传 corridorId 时）。 */
    @GetMapping("/corridors")
    public List<CorridorDto> listCorridors() {
        return service.listCorridors();
    }

    /** 查询让渡不可变历史；可选 corridorId 过滤。 */
    @GetMapping("/yields")
    public List<YieldRecordDto> listYields(
            @RequestParam(name = "corridorId", required = false) String corridorId) {
        return service.listYields(corridorId);
    }

    /** 查询占用处置不可变事件。 */
    @GetMapping("/occupancies/{occupancyId}/events")
    public List<OccupancyEventDto> listOccupancyEvents(@PathVariable String occupancyId) {
        return service.listOccupancyEvents(occupancyId);
    }

    /** 查询单个占用。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyResult getOccupancy(@PathVariable String occupancyId) {
        return service.getOccupancy(occupancyId);
    }
}
