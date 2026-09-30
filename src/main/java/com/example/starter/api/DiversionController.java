package com.example.starter.api;

import com.example.starter.api.dto.AirportCreateRequest;
import com.example.starter.api.dto.DepartureRequest;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyView;
import com.example.starter.api.dto.ReservationRequest;
import com.example.starter.service.DiversionService;
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
 * 备降机场预留 API：机场容量注册、按优先级与 UTC 左闭右开窗口申请预留、
 * 起飞标记与按机场时段查询占用及不可变替换链。
 */
@RestController
@RequestMapping("/api/airspace/diversion")
public class DiversionController {

    private final DiversionService service;

    public DiversionController(DiversionService service) {
        this.service = service;
    }

    /** 创建备降机场；容量非正返回 422。 */
    @PostMapping("/airports")
    public ResponseEntity<MutationResponse> createAirport(
            @Valid @RequestBody AirportCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createAirport(request));
    }

    /** 申请预留；窗口重叠且无法合法挤出返回 409，参数/机场非法返回 422。 */
    @PostMapping("/reservations")
    public ResponseEntity<MutationResponse> reserve(
            @Valid @RequestBody ReservationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserve(request));
    }

    /** 标记预留已起飞；已起飞/已挤出重复操作返回 409。 */
    @PostMapping("/reservations/depart")
    public ResponseEntity<MutationResponse> depart(
            @Valid @RequestBody DepartureRequest request) {
        return ResponseEntity.ok(service.depart(request));
    }

    /** 按机场与 UTC 左闭右开时段查询当前占用、优先级与不可变替换链。 */
    @GetMapping("/airports/{airportId}/occupancy")
    public OccupancyView occupancy(@PathVariable String airportId,
                                   @RequestParam("from") long from,
                                   @RequestParam("to") long to) {
        return service.getOccupancy(airportId, from, to);
    }
}
