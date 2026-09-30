package com.example.starter.playout.api;

import com.example.starter.playout.RegionalPlayoutService;
import com.example.starter.playout.api.Dtos.CreateRegionGrantRequest;
import com.example.starter.playout.api.Dtos.CreateRegionRequest;
import com.example.starter.playout.api.Dtos.RegionAllocationListResponse;
import com.example.starter.playout.api.Dtos.RegionGrantResponse;
import com.example.starter.playout.api.Dtos.RegionResponse;
import com.example.starter.playout.api.Dtos.RegionalAllocationRequest;
import com.example.starter.playout.api.Dtos.RegionalAllocationResponse;
import com.example.starter.playout.api.Dtos.RevokeRegionGrantRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 区域切换播出编排 REST API：区域与区域授权管理、多区域时段整单提交、占位查询。
 * 成功响应统一 200；400 参数错误、404 资源不存在、409 幂等/并发冲突、422 业务规则不满足（含冲突明细）。
 */
@Validated
@RestController
@RequestMapping("/api/regional")
public class RegionalPlayoutController {

    private final RegionalPlayoutService service;

    public RegionalPlayoutController(RegionalPlayoutService service) {
        this.service = service;
    }

    /** 创建区域。 */
    @PostMapping("/regions")
    public RegionResponse createRegion(@Valid @RequestBody CreateRegionRequest request) {
        return service.createRegion(request);
    }

    /** 为区域创建某素材的授权窗口（同 region+asset 版本递增）。 */
    @PostMapping("/regions/{regionId}/grants")
    public RegionGrantResponse createGrant(@PathVariable @NotBlank String regionId,
                                           @Valid @RequestBody CreateRegionGrantRequest request) {
        return service.createRegionGrant(regionId, request);
    }

    /** 撤销区域授权（幂等；只影响之后的编排，不改写历史占位）。 */
    @PostMapping("/grants/{grantId}/revoke")
    public RegionGrantResponse revokeGrant(@PathVariable long grantId,
                                           @Valid @RequestBody RevokeRegionGrantRequest request) {
        return service.revokeRegionGrant(grantId, request.requestId());
    }

    /** 多区域时段整单提交：任一区域授权失效或时段重叠则整单 422，不留部分区域占位。 */
    @PostMapping("/allocations")
    public RegionalAllocationResponse allocate(@Valid @RequestBody RegionalAllocationRequest request) {
        return service.allocate(request);
    }

    /** 编排员视图：查看各区域占位及（拒绝时的）冲突原因入口。 */
    @GetMapping("/allocations")
    public RegionAllocationListResponse listAll() {
        return service.listAll();
    }

    /** 区域播控方视图：只读本区域时段。 */
    @GetMapping("/regions/{regionId}/allocations")
    public RegionAllocationListResponse listByRegion(@PathVariable @NotBlank String regionId) {
        return service.listByRegion(regionId);
    }
}
