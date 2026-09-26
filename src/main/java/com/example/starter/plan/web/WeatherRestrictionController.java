package com.example.starter.plan.web;

import com.example.starter.plan.service.WeatherRestrictionService;
import com.example.starter.plan.web.dto.RearrangementView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.ReviseRestrictionRequest;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
import com.example.starter.plan.web.dto.DiagnoseResponse;
import com.example.starter.plan.web.dto.RestrictionResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路气象限速 API：限速令登记/撤销/修订、明细/历史/诊断查询与计划重排历史查询。
 * 所有查询为只读操作，不改变状态。
 */
@Validated
@RestController
@RequestMapping("/api/v1")
public class WeatherRestrictionController {

    private final WeatherRestrictionService service;

    public WeatherRestrictionController(WeatherRestrictionService service) {
        this.service = service;
    }

    /**
     * 登记气象限速令（版本 1）。区段、时段或速度非法返回 422。
     */
    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionResponse register(@Valid @RequestBody RegisterRestrictionRequest request) {
        return service.register(request);
    }

    /**
     * 撤销限速令当前生效版本（历史行保留，不再影响后续发布或改签）。
     */
    @PostMapping("/restrictions/{restrictionKey}/revoke")
    public RestrictionResponse revoke(@PathVariable String restrictionKey,
                                      @Valid @RequestBody RevokeRestrictionRequest request) {
        return service.revoke(restrictionKey, request);
    }

    /**
     * 修订限速令：原子撤销当前生效版本并追加版本加一的新行。
     */
    @PostMapping("/restrictions/{restrictionKey}/revise")
    public RestrictionResponse revise(@PathVariable String restrictionKey,
                                      @Valid @RequestBody ReviseRestrictionRequest request) {
        return service.revise(restrictionKey, request);
    }

    /**
     * 限速令明细查询：按可选区段与是否仅生效过滤。
     */
    @GetMapping("/restrictions")
    public List<RestrictionResponse> list(@RequestParam(required = false) String sectionId,
                                          @RequestParam(defaultValue = "false") boolean onlyActive) {
        return service.listRestrictions(sectionId, onlyActive);
    }

    /**
     * 限速诊断：指定区段与 UTC 左闭右开时段内的生效最低速度与各限速令相交分钟数。
     */
    @GetMapping("/restrictions/diagnose")
    public DiagnoseResponse diagnose(
            @RequestParam @NotBlank String sectionId,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant startUtc,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant endUtc) {
        return service.diagnose(sectionId, startUtc, endUtc);
    }

    /**
     * 限速令历史查询：指定业务键的全部版本（含已撤销），按版本升序。
     */
    @GetMapping("/restrictions/{restrictionKey}")
    public List<RestrictionResponse> history(@PathVariable String restrictionKey) {
        return service.getRestrictionHistory(restrictionKey);
    }

    /**
     * 计划重排历史查询：指定计划的全部重排记录（含逐段明细），按创建顺序升序。
     */
    @GetMapping("/plans/{scheduleKey}/rearrangements")
    public List<RearrangementView> rearrangements(@PathVariable String scheduleKey) {
        return service.getRearrangements(scheduleKey);
    }
}
