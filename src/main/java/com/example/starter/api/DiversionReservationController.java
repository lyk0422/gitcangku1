package com.example.starter.api;

import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyReportDto;
import com.example.starter.api.dto.ReservationCreateRequest;
import com.example.starter.api.dto.ReservationDepartRequest;
import com.example.starter.service.DiversionReservationService;
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
 * 备降机场预留 API：机场创建、窗口预留（含优先级挤出）、起飞登记与占用/替换链查询。
 */
@RestController
@RequestMapping("/api/airspace/diversion")
public class DiversionReservationController {

    private final DiversionReservationService service;

    public DiversionReservationController(DiversionReservationService service) {
        this.service = service;
    }

    /** 创建备降机场（容量 &gt;=1，否则 422）。 */
    @PostMapping("/airports")
    public ResponseEntity<MutationResponse> createAirport(
            @Valid @RequestBody AirportCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAirport(request));
    }

    /** 申请窗口预留；高优先级可整体挤出未起飞低优先级，重叠且无法挤出返回 409。 */
    @PostMapping("/reservations")
    public ResponseEntity<MutationResponse> reserve(
            @Valid @RequestBody ReservationCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(request));
    }

    /** 登记预留起飞；已被挤出不能起飞，起飞后不可被挤出。 */
    @PostMapping("/reservations/depart")
    public ResponseEntity<MutationResponse> depart(
            @Valid @RequestBody ReservationDepartRequest request) {
        return ResponseEntity.ok(service.depart(request));
    }

    /** 按机场与 UTC 左闭右开时段查询当前占用、优先级与不可变替换链。 */
    @GetMapping("/airports/{airportId}/occupancies")
    public OccupancyReportDto occupancies(@PathVariable String airportId,
                                          @RequestParam("from") long from,
                                          @RequestParam("to") long to) {
        return service.queryOccupancy(airportId, from, to);
    }
}
