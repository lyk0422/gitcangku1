package com.example.starter.api;

import com.example.starter.api.dto.BandConfigRequest;
import com.example.starter.api.dto.BandConfigResult;
import com.example.starter.api.dto.MutationResponse;
import com.example.starter.api.dto.OccupancyCancelRequest;
import com.example.starter.api.dto.OccupancyCreateRequest;
import com.example.starter.api.dto.OccupancyResult;
import com.example.starter.api.dto.VerticalDetailDto;
import com.example.starter.service.AltitudeService;
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
 * 空域高度层容量与航线垂直分离审查 API：
 * 区域高度带配置、占用创建/取消、按时段占用查询与垂直分离明细查询。
 */
@RestController
@RequestMapping("/api/airspace")
public class AltitudeController {

    private final AltitudeService altitudeService;

    public AltitudeController(AltitudeService altitudeService) {
        this.altitudeService = altitudeService;
    }

    /** 修改区域高度带配置（仅上调容量/新增不重叠带，携带 expectedVersion）。 */
    @PostMapping("/zones/bands")
    public ResponseEntity<MutationResponse> configureBands(
            @Valid @RequestBody BandConfigRequest request) {
        return ResponseEntity.ok(altitudeService.configureBands(request));
    }

    /** 查询区域当前高度带配置。 */
    @GetMapping("/zones/{zoneId}/bands")
    public BandConfigResult getBands(@PathVariable String zoneId) {
        return altitudeService.getBands(zoneId);
    }

    /** 为 CLEAR 审查创建高度层占用；满容量 429，审查 STALE 422。 */
    @PostMapping("/occupancies")
    public ResponseEntity<MutationResponse> createOccupancy(
            @Valid @RequestBody OccupancyCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                altitudeService.createOccupancy(request));
    }

    /** 取消占用（立即释放容量，历史保留）。 */
    @PostMapping("/occupancies/cancel")
    public ResponseEntity<MutationResponse> cancelOccupancy(
            @Valid @RequestBody OccupancyCancelRequest request) {
        return ResponseEntity.ok(altitudeService.cancelOccupancy(request));
    }

    /** 按 occupancyId 查询占用。 */
    @GetMapping("/occupancies/{occupancyId}")
    public OccupancyResult getOccupancy(@PathVariable String occupancyId) {
        return altitudeService.getOccupancy(occupancyId);
    }

    /** 按时段（左闭右开，epoch 毫秒 UTC）查询占用，可按区域/高度带过滤。 */
    @GetMapping("/occupancies")
    public List<OccupancyResult> findOccupancies(
            @RequestParam("startTime") Long startTime,
            @RequestParam("endTime") Long endTime,
            @RequestParam(value = "zoneId", required = false) String zoneId,
            @RequestParam(value = "bandLower", required = false) Integer bandLower) {
        return altitudeService.findOccupancies(startTime, endTime, zoneId, bandLower);
    }

    /** 查询审查的航线垂直分离明细。 */
    @GetMapping("/reviews/{reviewId}/vertical-details")
    public List<VerticalDetailDto> getVerticalDetails(@PathVariable String reviewId) {
        return altitudeService.getVerticalDetails(reviewId);
    }
}
