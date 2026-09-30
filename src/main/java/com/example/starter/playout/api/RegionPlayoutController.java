package com.example.starter.playout.api;

import com.example.starter.playout.RegionPlayoutService;
import com.example.starter.playout.api.Dtos.CreateRegionGrantRequest;
import com.example.starter.playout.api.Dtos.CreateRegionRequest;
import com.example.starter.playout.api.Dtos.RegionGrantResponse;
import com.example.starter.playout.api.Dtos.RegionOccupancyResponse;
import com.example.starter.playout.api.Dtos.RegionResponse;
import com.example.starter.playout.api.Dtos.RegionScheduleResponse;
import com.example.starter.playout.api.Dtos.RevokeRegionGrantRequest;
import com.example.starter.playout.api.Dtos.SubmitRegionScheduleRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 区域切换 REST API：区域与区域授权管理、多区域时段编排提交、占位查询。
 * 成功响应统一 200；400 参数错误、404 资源不存在、409 幂等冲突、422 整单业务规则不满足。
 */
@Validated
@RestController
@RequestMapping("/api/region-playout")
public class RegionPlayoutController {

    private final RegionPlayoutService service;

    public RegionPlayoutController(RegionPlayoutService service) {
        this.service = service;
    }

    /** 创建区域。 */
    @PostMapping("/regions")
    public RegionResponse createRegion(@Valid @RequestBody CreateRegionRequest request) {
        return service.createRegion(request);
    }

    /** 创建区域授权。 */
    @PostMapping("/grants")
    public RegionGrantResponse createGrant(@Valid @RequestBody CreateRegionGrantRequest request) {
        return service.createRegionGrant(request);
    }

    /** 撤销区域授权（幂等）；只影响之后的编排，历史占位不追改。 */
    @PostMapping("/grants/{grantId}/revoke")
    public RegionGrantResponse revokeGrant(@PathVariable long grantId,
                                           @Valid @RequestBody RevokeRegionGrantRequest request) {
        return service.revokeRegionGrant(grantId, request.requestId());
    }

    /** 提交多区域时段编排（整单原子，携带 requestId 幂等）。 */
    @PostMapping("/schedules")
    public RegionScheduleResponse submitSchedule(@Valid @RequestBody SubmitRegionScheduleRequest request) {
        return service.submitSchedule(request);
    }

    /** 编排员查看各区域占位。 */
    @GetMapping("/allocations")
    public List<RegionOccupancyResponse> listAllocations() {
        return service.listAllAllocations();
    }

    /** 区域播控方只读本区域时段。 */
    @GetMapping("/regions/{regionId}/allocations")
    public List<RegionOccupancyResponse> regionAllocations(@PathVariable @NotBlank String regionId) {
        return service.listRegionAllocations(regionId);
    }
}
