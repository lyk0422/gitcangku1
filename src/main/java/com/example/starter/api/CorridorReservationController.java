package com.example.starter.api;

import com.example.starter.api.dto.AvailabilityResult;
import com.example.starter.api.dto.CorridorCapacityAdjustRequest;
import com.example.starter.api.dto.CorridorCreateRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.ReservationCancelRequest;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationResult;
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

import java.util.List;

/**
 * 航路走廊时段预约与容量占用核验 API。
 */
@RestController
@RequestMapping("/api/airspace")
public class CorridorReservationController {

    private final CorridorReservationService service;

    public CorridorReservationController(CorridorReservationService service) {
        this.service = service;
    }

    /** 创建走廊（矩形区域与同时容量上限）。 */
    @PostMapping("/corridors")
    public ResponseEntity<MutationResponse> createCorridor(
            @Valid @RequestBody CorridorCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCorridor(request));
    }

    /** 调整走廊容量（仅可上调，立即生效）。 */
    @PostMapping("/corridors/capacity")
    public ResponseEntity<MutationResponse> adjustCapacity(
            @Valid @RequestBody CorridorCapacityAdjustRequest request) {
        return ResponseEntity.ok(service.adjustCapacity(request));
    }

    /** 查询走廊在某一时刻的容量占用（只读）。 */
    @GetMapping("/corridors/{corridorId}/occupancy")
    public OccupancyResult getOccupancy(@PathVariable String corridorId,
                                        @RequestParam String at) {
        return service.getOccupancy(corridorId, at);
    }

    /** 探测走廊某时段是否可预约（只读，不建立预约）。 */
    @GetMapping("/corridors/{corridorId}/availability")
    public AvailabilityResult probeAvailability(@PathVariable String corridorId,
                                                @RequestParam String start,
                                                @RequestParam String end) {
        return service.probeAvailability(corridorId, start, end);
    }

    /** 查询走廊在指定时段内的全部预约（含已取消历史，只读）。 */
    @GetMapping("/corridors/{corridorId}/reservations")
    public List<ReservationResult> listReservations(@PathVariable String corridorId,
                                                    @RequestParam String from,
                                                    @RequestParam String to) {
        return service.listReservations(corridorId, from, to);
    }

    /** 创建走廊时段预约。 */
    @PostMapping("/reservations")
    public ResponseEntity<MutationResponse> createReservation(
            @Valid @RequestBody ReservationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createReservation(request));
    }

    /** 取消走廊时段预约（立即从容量计数中移除，历史保留）。 */
    @PostMapping("/reservations/cancel")
    public ResponseEntity<MutationResponse> cancelReservation(
            @Valid @RequestBody ReservationCancelRequest request) {
        return ResponseEntity.ok(service.cancelReservation(request));
    }

    /** 按 reservationId 查询预约（含已取消历史）。 */
    @GetMapping("/reservations/{reservationId}")
    public ReservationResult getReservation(@PathVariable String reservationId) {
        return service.getReservation(reservationId);
    }
}
