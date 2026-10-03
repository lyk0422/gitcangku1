package com.example.starter.coral;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 珊瑚移植样方业务入口。
 */
@RestController
@RequestMapping("/api/coral")
public class CoralController {

    private final CoralService service;

    public CoralController(CoralService service) {
        this.service = service;
    }

    /** 登记移植：固化来源苗圃、样方与UTC时刻。 */
    @PostMapping("/transplants")
    @ResponseStatus(HttpStatus.CREATED)
    public TransplantView register(@Valid @RequestBody RegisterTransplantRequest request) {
        return service.registerTransplant(request);
    }

    /** 复核单株：标为存活、死亡或待复核。 */
    @PostMapping("/transplants/{plantCode}/reviews")
    public TransplantView review(@PathVariable String plantCode,
            @Valid @RequestBody ReviewRequest request) {
        return service.review(plantCode, request);
    }

    /** 封存样方：固化各状态数量与植株清单。 */
    @PostMapping("/plots/{plotCode}/seal")
    public SealView seal(@PathVariable String plotCode,
            @Valid @RequestBody SealRequest request) {
        return service.sealPlot(plotCode, request);
    }

    /** 查询单株：来源、当前样方、复核链与封存摘要。 */
    @GetMapping("/transplants/{plantCode}")
    public TransplantView getTransplant(@PathVariable String plantCode) {
        return service.getTransplant(plantCode);
    }

    /** 查询样方：容量、各状态数量与封存快照。 */
    @GetMapping("/plots/{plotCode}")
    public PlotView getPlot(@PathVariable String plotCode) {
        return service.getPlot(plotCode);
    }
}