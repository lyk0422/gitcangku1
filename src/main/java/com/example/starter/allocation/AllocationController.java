package com.example.starter.allocation;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.allocation.dto.AllocationRequest;
import com.example.starter.allocation.dto.AllocationView;
import com.example.starter.allocation.dto.CancelRequest;
import com.example.starter.allocation.dto.CompletionRequest;
import com.example.starter.station.GroundStation;
import com.example.starter.station.StationRepository;

/**
 * 接收时隙分配接口：申请、完成、取消与时隙/实绩查询。
 */
@RestController
@RequestMapping("/api")
public class AllocationController {

    private final AllocationService allocationService;
    private final StationRepository stationRepository;

    public AllocationController(AllocationService allocationService,
            StationRepository stationRepository) {
        this.allocationService = allocationService;
        this.stationRepository = stationRepository;
    }

    /**
     * 申请接收时隙；同 requestId 同参重放返回 200 与原记录，新建返回 201。
     */
    @PostMapping("/allocations")
    public ResponseEntity<AllocationView> allocate(@Valid @RequestBody AllocationRequest request) {
        boolean replay = allocationService.findByRequestId(request.requestId()).isPresent();
        AllocationView body = view(allocationService.allocate(request));
        return ResponseEntity.status(replay ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    /**
     * 完成回报；同 requestId 同参重放返回原记录。
     */
    @PostMapping("/allocations/{id}/complete")
    public AllocationView complete(@PathVariable long id,
            @Valid @RequestBody CompletionRequest request) {
        return view(allocationService.complete(id, request));
    }

    /**
     * 取消分配并释放天线。
     */
    @PostMapping("/allocations/{id}/cancel")
    public AllocationView cancel(@PathVariable long id,
            @Valid @RequestBody CancelRequest request) {
        return view(allocationService.cancel(id, request));
    }

    /**
     * 按主键查询分配。
     */
    @GetMapping("/allocations/{id}")
    public AllocationView findById(@PathVariable long id) {
        return view(allocationService.findById(id));
    }

    /**
     * 待重新分配列表。
     */
    @GetMapping("/allocations/pending-reassignment")
    public List<AllocationView> pendingReassignment() {
        return views(allocationService.pendingReassignment());
    }

    /**
     * 站点时隙时间线。
     */
    @GetMapping("/stations/{stationCode}/timeline")
    public List<AllocationView> timeline(@PathVariable @NotBlank String stationCode,
            @RequestParam Instant from, @RequestParam Instant to) {
        return views(allocationService.timeline(stationCode, from, to));
    }

    /**
     * 站点接收实绩。
     */
    @GetMapping("/stations/{stationCode}/receptions")
    public List<AllocationView> receptions(@PathVariable @NotBlank String stationCode) {
        return views(allocationService.receptions(stationCode));
    }

    private AllocationView view(SlotAllocation allocation) {
        String code = stationRepository.findById(allocation.stationId())
                .map(GroundStation::code).orElse("?");
        return AllocationView.from(allocation, code);
    }

    private List<AllocationView> views(List<SlotAllocation> allocations) {
        Map<Long, String> codes = allocations.stream()
                .map(SlotAllocation::stationId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(),
                        id -> stationRepository.findById(id).map(GroundStation::code).orElse("?")));
        return allocations.stream()
                .map(a -> AllocationView.from(a, codes.get(a.stationId())))
                .toList();
    }
}