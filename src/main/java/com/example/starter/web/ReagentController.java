package com.example.starter.web;

import com.example.starter.service.ReagentService;
import com.example.starter.web.dto.ApiDtos.BottleDetailView;
import com.example.starter.web.dto.ApiDtos.BottleView;
import com.example.starter.web.dto.ApiDtos.DispenseRequest;
import com.example.starter.web.dto.ApiDtos.DispenseView;
import com.example.starter.web.dto.ApiDtos.RecallRequest;
import com.example.starter.web.dto.ApiDtos.RecallView;
import com.example.starter.web.dto.ApiDtos.ReceiveRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 试剂瓶接收、开封、领用、召回与查询接口。 */
@RestController
@RequestMapping("/api")
public class ReagentController {

    private final ReagentService service;

    public ReagentController(ReagentService service) {
        this.service = service;
    }

    @PostMapping("/bottles")
    @ResponseStatus(HttpStatus.CREATED)
    public BottleView receive(@Valid @RequestBody ReceiveRequest request) {
        return service.receive(request.lotNumber(), request.capacityMl(), request.supplierExpiryUtc());
    }

    @PostMapping("/bottles/{id}/open")
    public BottleView open(@PathVariable long id) {
        return service.open(id);
    }

    @PostMapping("/bottles/{id}/dispenses")
    public DispenseView dispense(@PathVariable long id, @Valid @RequestBody DispenseRequest request) {
        return service.dispense(id, request.requestId(), request.amountMl());
    }

    @PostMapping("/lots/{lotNumber}/recall")
    public RecallView recall(@PathVariable String lotNumber, @Valid @RequestBody RecallRequest request) {
        return service.recall(lotNumber, request.reason());
    }

    @GetMapping("/bottles/{id}")
    public BottleDetailView detail(@PathVariable long id) {
        return service.detail(id);
    }
}