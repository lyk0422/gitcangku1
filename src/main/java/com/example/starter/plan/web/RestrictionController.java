package com.example.starter.plan.web;

import com.example.starter.plan.service.WeatherRestrictionService;
import com.example.starter.plan.web.dto.RearrangementView;
import com.example.starter.plan.web.dto.RegisterRestrictionRequest;
import com.example.starter.plan.web.dto.RestrictionImpactView;
import com.example.starter.plan.web.dto.RestrictionView;
import com.example.starter.plan.web.dto.RevokeRestrictionRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 铁路气象限速 API：限速令登记/撤销/明细查询、计划重排历史与限速影响诊断。
 * 所有查询只读，不改变状态。
 */
@RestController
@RequestMapping("/api/v1")
public class RestrictionController {

    private final WeatherRestrictionService service;

    public RestrictionController(WeatherRestrictionService service) {
        this.service = service;
    }

    /**
     * 登记气象限速令：按区段、UTC 左闭右开时段与最高速度（10～300 km/h）登记；
     * 区段/时段/速度非法返回 422。
     */
    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    public RestrictionView register(@Valid @RequestBody RegisterRestrictionRequest request) {
        return service.register(request);
    }

    /**
     * 撤销限速令：仅影响后续发布或改签，不改写已生成的重排记录。
     */
    @PostMapping("/restrictions/{id}/revoke")
    public RestrictionView revoke(@PathVariable long id,
                                  @Valid @RequestBody RevokeRestrictionRequest request) {
        return service.revoke(id, request);
    }

    /**
     * 限速令明细查询：可按区段过滤，默认仅返回生效中的限速令。
     */
    @GetMapping("/restrictions")
    public List<RestrictionView> list(@RequestParam(required = false) String sectionId,
                                      @RequestParam(defaultValue = "false") boolean includeRevoked) {
        return service.listRestrictions(sectionId, includeRevoked);
    }

    /**
     * 计划重排历史查询：按追加顺序返回不可变重排记录（含限速令版本快照、原/新计划时刻）。
     */
    @GetMapping("/plans/{scheduleKey}/rearrangements")
    public List<RearrangementView> getRearrangements(@PathVariable String scheduleKey) {
        return service.getRearrangements(scheduleKey);
    }

    /**
     * 限速影响诊断：逐段返回受影响运行分钟数（实际/要求/差额）与所需整体顺延，只读。
     */
    @GetMapping("/plans/{scheduleKey}/restriction-impact")
    public RestrictionImpactView getImpact(@PathVariable String scheduleKey) {
        return service.getImpact(scheduleKey);
    }
}
