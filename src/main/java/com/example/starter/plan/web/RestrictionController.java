package com.example.starter.plan.web;

import com.example.starter.plan.service.RestrictionService;
import com.example.starter.plan.web.dto.RestrictionActionRequest;
import com.example.starter.plan.web.dto.RestrictionCreateRequest;
import com.example.starter.plan.web.dto.RestrictionResponse;
import com.example.starter.plan.web.dto.RestrictionReviseRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 气象限速令 API：登记、修订、撤销与查询。
 * 时段为 UTC 左闭右开区间；速度范围 10~300km/h；区段或时段非法返回 422。
 */
@RestController
@RequestMapping("/api/v1/restrictions")
public class RestrictionController {

    private final RestrictionService service;

    public RestrictionController(RestrictionService service) {
        this.service = service;
    }

    /**
     * 登记限速令；同一区段同一时刻允许叠加多条，生效速度取最低值。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse create(@Valid @RequestBody RestrictionCreateRequest request) {
        return service.create(request);
    }

    /**
     * 修订限速令时段与最高速度，版本加一。
     */
    @PutMapping("/{restrictionKey}")
    public RestrictionResponse revise(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RestrictionReviseRequest request) {
        return service.revise(restrictionKey, request);
    }

    /**
     * 撤销限速令，历史保留，不再影响后续发布或改签。
     */
    @PostMapping("/{restrictionKey}/revoke")
    public RestrictionResponse revoke(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RestrictionActionRequest request) {
        return service.revoke(restrictionKey, request);
    }

    /**
     * 限速令明细（只读）。
     */
    @GetMapping("/{restrictionKey}")
    public RestrictionResponse getRestriction(@PathVariable String restrictionKey) {
        return service.getRestriction(restrictionKey);
    }

    /**
     * 限速令列表（只读），可按区段过滤。
     */
    @GetMapping
    public List<RestrictionResponse> listRestrictions(
            @RequestParam(required = false) String sectionId) {
        return service.listRestrictions(sectionId);
    }
}