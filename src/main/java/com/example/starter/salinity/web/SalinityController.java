package com.example.starter.salinity.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.starter.salinity.command.AddErratumCommand;
import com.example.starter.salinity.command.AdoptSampleCommand;
import com.example.starter.salinity.command.SealVoyageCommand;
import com.example.starter.salinity.command.SubmitSampleCommand;
import com.example.starter.salinity.service.SalinityService;
import com.example.starter.salinity.view.SampleView;
import com.example.starter.salinity.view.VoyageDataView;

import jakarta.validation.Valid;

/**
 * 盐度采样业务 HTTP 入口：
 * 提交样本、质控采纳、航次封存、勘误追加与航次数据查询。
 */
@RestController
@RequestMapping("/api/salinity")
public class SalinityController {

    private final SalinityService service;

    public SalinityController(SalinityService service) {
        this.service = service;
    }

    /** 提交一条采样；携带 requestId 的同参重放返回原样本。 */
    @PostMapping("/samples")
    @ResponseStatus(HttpStatus.CREATED)
    public SampleView submit(@Valid @RequestBody SubmitSampleCommand command) {
        return service.submit(command);
    }

    /** 质控采纳：将指定样本置为其站位深度的唯一采纳版本。 */
    @PostMapping("/adoptions")
    public SampleView adopt(@Valid @RequestBody AdoptSampleCommand command) {
        return service.adopt(command);
    }

    /** 封存航次：各站位 0、50、100 米采纳样本固化为剖面。 */
    @PostMapping("/seals")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seal(@Valid @RequestBody SealVoyageCommand command) {
        service.seal(command);
    }

    /** 封存后追加勘误；原剖面不变。 */
    @PostMapping("/errata")
    @ResponseStatus(HttpStatus.CREATED)
    public void addErratum(@Valid @RequestBody AddErratumCommand command) {
        service.addErratum(command);
    }

    /** 查询航次：原始样本、采纳版本、冻结剖面、勘误。 */
    @GetMapping("/voyages/{voyageCode}")
    public VoyageDataView query(@PathVariable String voyageCode) {
        return service.query(voyageCode);
    }
}
