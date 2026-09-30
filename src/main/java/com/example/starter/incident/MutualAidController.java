package com.example.starter.incident;

import java.util.List;

import com.example.starter.incident.dto.Requests.AidRequestRequest;
import com.example.starter.incident.dto.Requests.AidReturnRequest;
import com.example.starter.incident.dto.Requests.ResourceRegisterRequest;
import com.example.starter.incident.dto.Responses.AidLeaseDetailView;
import com.example.starter.incident.dto.Responses.AidLeaseView;
import com.example.starter.incident.dto.Responses.ResourceInventoryView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨事件互助资源征调 REST API。
 * 资源可用量登记在来源/目标事件路径下；征调与归还为全局 requestId 维度。
 * 写操作均要求 X-Actor-Id 请求头标识操作人。
 */
@RestController
@RequestMapping("/api/incidents")
public class MutualAidController {

    private final MutualAidService service;

    public MutualAidController(MutualAidService service) {
        this.service = service;
    }

    /**
     * 登记事件资源可用量（合成数据入口）；同事件同资源重复登记相同数量幂等，不同数量 409。
     */
    @PostMapping("/{incidentKey}/resources")
    public ResponseEntity<ResourceInventoryView> register(@PathVariable String incidentKey,
                                                          @RequestHeader("X-Actor-Id") String actor,
                                                          @RequestBody ResourceRegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.registerInventory(incidentKey, actor, req));
    }

    /**
     * 查询事件登记的全部资源可用量（只读）。
     */
    @GetMapping("/{incidentKey}/resources")
    public List<ResourceInventoryView> listResources(@PathVariable String incidentKey) {
        return service.listInventories(incidentKey);
    }

    /**
     * 提交跨事件资源征调：原子扣减来源可用量并为目标登记租约。
     */
    @PostMapping("/aid/requests")
    public AidLeaseDetailView requestAid(@RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody AidRequestRequest req) {
        return service.requestAid(actor, req);
    }

    /**
     * 按 requestId 归还租约：按实际数量累计结算，恰好还完关闭租约。
     */
    @PostMapping("/aid/requests/{requestId}/returns")
    public AidLeaseDetailView returnLease(@PathVariable String requestId,
                                          @RequestHeader("X-Actor-Id") String actor,
                                          @RequestBody AidReturnRequest req) {
        return service.returnLease(requestId, actor, req);
    }

    /**
     * 查询单条征调关联账务：来源扣减账、目标租约与分次归还流水（只读）。
     */
    @GetMapping("/aid/requests/{requestId}")
    public AidLeaseDetailView getLease(@PathVariable String requestId) {
        return service.getLease(requestId);
    }

    /**
     * 查询事件关联的全部征调租约（作为来源或目标，含历史已关闭租约）。
     */
    @GetMapping("/{incidentKey}/aid-leases")
    public List<AidLeaseView> listLeases(@PathVariable String incidentKey) {
        return service.listLeasesByIncident(incidentKey);
    }
}
