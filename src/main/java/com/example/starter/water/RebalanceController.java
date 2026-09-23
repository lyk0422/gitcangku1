package com.example.starter.water;

import com.example.starter.water.dto.Dtos.BlockResponse;
import com.example.starter.water.dto.Dtos.ConfigureBlockRequest;
import com.example.starter.water.dto.Dtos.ConfigureSourcesRequest;
import com.example.starter.water.dto.Dtos.RebalanceActivateRequest;
import com.example.starter.water.dto.Dtos.RebalancePreviewRequest;
import com.example.starter.water.dto.Dtos.RebalancePreviewResponse;
import com.example.starter.water.dto.Dtos.RebalanceResponse;
import com.example.starter.water.dto.Dtos.SourceResponse;
import com.example.starter.water.dto.Dtos.WriteoffRequest;
import com.example.starter.water.dto.Dtos.WriteoffResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多水源配额矩阵与守恒重平衡 REST 接口。
 *
 * <p>配置类命令携带 commandKey 保证幂等；重平衡激活携带 requestId（幂等）与 rebalanceKey（唯一）。
 * 预览不落库；证据只读并按水源、区块稳定排序。</p>
 */
@RestController
@RequestMapping("/api")
public class RebalanceController {

    private final RebalanceService service;

    public RebalanceController(RebalanceService service) {
        this.service = service;
    }

    /** 配置窗口水源及供给上限（每窗口 1~10 个，仅可配置一次）。 */
    @PostMapping("/windows/{windowId}/sources")
    public List<SourceResponse> configureSources(@PathVariable long windowId,
                                                 @RequestBody ConfigureSourcesRequest request) {
        return service.configureSources(request.commandKey(), windowId, request.sources());
    }

    /** 查询窗口水源。 */
    @GetMapping("/windows/{windowId}/sources")
    public List<SourceResponse> getSources(@PathVariable long windowId) {
        return service.getSources(windowId);
    }

    /** 登记区块：适用水源白名单与各水源初始额度。 */
    @PostMapping("/windows/{windowId}/blocks")
    public BlockResponse configureBlock(@PathVariable long windowId,
                                        @RequestBody ConfigureBlockRequest request) {
        return service.configureBlock(request.commandKey(), windowId, request.blockId(),
                request.applicableSources(), request.quotas());
    }

    /** 查询窗口全部区块矩阵。 */
    @GetMapping("/windows/{windowId}/blocks")
    public List<BlockResponse> getBlocks(@PathVariable long windowId) {
        return service.getBlocks(windowId);
    }

    /** 对区块-水源单元格登记用水核销；操作人由 X-Actor-Id 提供。 */
    @PostMapping("/windows/{windowId}/blocks/{blockId}/writeoff")
    public WriteoffResponse writeoff(@PathVariable long windowId, @PathVariable String blockId,
                                     @RequestBody WriteoffRequest request,
                                     @RequestHeader("X-Actor-Id") String actor) {
        return service.writeoff(request.commandKey(), request.writeoffKey(), windowId, blockId,
                request.sourceId(), request.volume(), actor);
    }

    /** 关闭窗口；关闭后重平衡整单 409。 */
    @PostMapping("/windows/{windowId}/close")
    public void closeWindow(@PathVariable long windowId,
                            @RequestBody com.example.starter.water.dto.Dtos.CommandRequest request) {
        service.closeWindow(request.commandKey(), windowId);
    }

    /** 重平衡预览：规范化明细并按完整矩阵计算后态，不落库。 */
    @PostMapping("/rebalances/preview")
    public RebalancePreviewResponse preview(@RequestBody RebalancePreviewRequest request) {
        return service.preview(request);
    }

    /** 激活重平衡单：单事务守恒搬水并冻结证据。 */
    @PostMapping("/rebalances/activate")
    public RebalanceResponse activate(@RequestBody RebalanceActivateRequest request) {
        return service.activate(request);
    }

    /** 查询重平衡只读证据（明细、前后矩阵、上限、核销量快照）。 */
    @GetMapping("/rebalances/{rebalanceKey}/evidence")
    public RebalanceResponse getEvidence(@PathVariable String rebalanceKey) {
        return service.getEvidence(rebalanceKey);
    }
}
