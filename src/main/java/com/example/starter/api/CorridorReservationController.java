package com.example.starter.api;

import com.example.starter.api.dto.CorridorCapacityRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ProbeResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationHistoryDto;
import com.example.starter.api.dto.ReservationWindowDto;
import com.example.starter.service.CorridorReservationService;
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

/**
 * 航路走廊与时段预约 API。
 */
@RestController
@RequestMapping("/api/corridors")
public class CorridorReservationController {

    private final CorridorReservationService service;

    public CorridorReservationController(CorridorReservationService service) {
        this.service = service;
    }

    /** 创建走廊。 */
    @PostMapping
    public ResponseEntity<MutationResponse> createCorridor(
            @Valid @RequestBody CorridorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCorridor(request));
    }

    /** 上调走廊容量。 */
    @PostMapping("/capacity")
    public MutationResponse adjustCapacity(@Valid @RequestBody CorridorCapacityRequest request) {
        return service.adjustCapacity(request);
    }

    /** 创建预约。 */
    @PostMapping("/reservations")
    public ResponseEntity<MutationResponse> createReservation(
            @Valid @RequestBody ReservationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createReservation(request));
    }

    /** 取消预约。 */
    @PostMapping("/reservations/cancel")
    public MutationResponse cancelReservation(
            @Valid @RequestBody ReservationCancelRequest request) {
        return service.cancelReservation(request);
    }

    /** 走廊在某时刻的当前占用（只读）。 */
    @GetMapping("/{corridorId}/occupancy")
    public OccupancyView occupancyAt(@PathVariable String corridorId,
                                     @RequestParam("at") long at) {
        return service.occupancyAt(corridorId, at);
    }

    /** 走廊与 [from,to) 重叠的生效预约（只读）。 */
    @GetMapping("/{corridorId}/reservations")
    public ReservationWindowDto activeWindow(@PathVariable String corridorId,
                                             @RequestParam("from") long from,
                                             @RequestParam("to") long to) {
        return service.activeWindow(corridorId, from, to);
    }

    /** 探测时间窗是否可预约（只读，不建立预约）。 */
    @GetMapping("/{corridorId}/probe")
    public ProbeResult probe(@PathVariable String corridorId,
                             @RequestParam("start") long start,
                             @RequestParam("end") long end) {
        return service.probe(corridorId, start, end);
    }

    /** 走廊预约历史（含已取消）。 */
    @GetMapping("/{corridorId}/history")
    public ReservationHistoryDto history(@PathVariable String corridorId) {
        return service.history(corridorId);
    }
}
