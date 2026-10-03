package com.example.starter.timber;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.starter.timber.web.ApiException;
import com.example.starter.timber.web.TimberDtos.BatchView;
import com.example.starter.timber.web.TimberDtos.RegisterSourceRequest;
import com.example.starter.timber.web.TimberDtos.SignRequest;
import com.example.starter.timber.web.TimberDtos.SignResponse;
import com.example.starter.timber.web.TimberDtos.SourceResponse;
import com.example.starter.timber.web.TimberDtos.SplitRequest;
import com.example.starter.timber.web.TimberDtos.SplitResponse;
import com.example.starter.timber.web.TimberDtos.TraceNode;
import com.example.starter.timber.web.TimberDtos.TraceResponse;
import com.example.starter.timber.web.TimberDtos.TransferRequest;
import com.example.starter.timber.web.TimberDtos.TransferResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 监管链业务服务：来源登记、批次拆分、移交签收、追溯查询。
 * 拆分、移交与签收按requestId幂等：同参重放原结果，异参返回409；
 * 拆分与移交通过对批次行加锁互斥，同一叶子批次竞争时只有一个操作成功；
 * 业务校验失败抛异常回滚，请求键与子批次一并撤销，不会留下部分数据。
 */
@Service
public class TimberService {

    private final TimberRepository repository;
    private final ObjectMapper objectMapper;

    public TimberService(TimberRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** 登记来源批次并生成根批次；凭证编号重复返回409。 */
    @Transactional
    public SourceResponse registerSource(RegisterSourceRequest request) {
        if (repository.findSourceByVoucher(request.voucherNo()).isPresent()) {
            throw ApiException.conflict("SOURCE_EXISTS", "采伐凭证编号已登记: " + request.voucherNo());
        }
        TimberSource source = repository.insertSource(request.voucherNo(), request.species(),
                request.weightKg(), request.holder());
        String rootBatchNo = "BATCH-" + request.voucherNo();
        repository.insertBatch(rootBatchNo, source.id(), null, source.voucherNo(), source.species(),
                source.weightKg(), source.holder());
        return new SourceResponse(source.voucherNo(), source.species(), source.weightKg(),
                source.holder(), rootBatchNo);
    }

    /** 拆分叶子批次：重量不守恒422，重复子批次编号422/409，失败不留下部分子批次。 */
    @Transactional
    public SplitResponse split(String parentBatchNo, SplitRequest request) {
        List<String> parts = new ArrayList<>();
        parts.add(parentBatchNo);
        request.children().stream()
                .map(child -> child.batchNo() + ":" + child.weightKg())
                .sorted()
                .forEach(parts::add);
        String fingerprint = Fingerprints.of("SPLIT", parts);
        Optional<RequestLogRow> replay = beginRequest(request.requestId(), "SPLIT", fingerprint);
        if (replay.isPresent()) {
            return readReplay(replay.get(), SplitResponse.class);
        }
        SplitResponse response = doSplit(parentBatchNo, request);
        repository.completeRequest(request.requestId(), writeJson(response));
        return response;
    }

    private SplitResponse doSplit(String parentBatchNo, SplitRequest request) {
        TimberBatch parent = repository.lockBatchByNo(parentBatchNo)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + parentBatchNo));
        if (!parent.leaf()) {
            throw ApiException.unprocessable("BATCH_ALREADY_SPLIT", "父批次已拆分，仅保留来源节点，不能再拆分");
        }
        if (repository.findPendingTransferForBatch(parent.id()).isPresent()) {
            throw ApiException.conflict("TRANSFER_PENDING", "批次存在待签收移交，不能拆分: " + parentBatchNo);
        }
        Optional<String> duplicate = SplitValidator.checkDistinctBatchNos(request.children());
        if (duplicate.isPresent()) {
            throw ApiException.unprocessable("DUPLICATE_CHILD_BATCH_NO", duplicate.get());
        }
        Optional<String> weightError = SplitValidator.checkWeightConservation(parent.weightKg(), request.children());
        if (weightError.isPresent()) {
            throw ApiException.unprocessable("WEIGHT_NOT_CONSERVED", weightError.get());
        }
        for (SplitChildInput child : request.children()) {
            if (repository.findBatchByNo(child.batchNo()).isPresent()) {
                throw ApiException.conflict("BATCH_NO_EXISTS", "子批次编号已存在: " + child.batchNo());
            }
        }
        List<BatchView> children = new ArrayList<>();
        for (SplitChildInput child : request.children()) {
            TimberBatch created = repository.insertBatch(child.batchNo(), parent.sourceId(), parent.id(),
                    parent.voucherNo(), parent.species(), child.weightKg(), parent.holder());
            children.add(toView(created));
        }
        repository.markNonLeaf(parent.id());
        return new SplitResponse(parentBatchNo, children);
    }

    /** 申请移交：仅未移交、未拆分的叶子批次可移交，持有人不符返回409。 */
    @Transactional
    public TransferResponse createTransfer(String batchNo, TransferRequest request) {
        String fingerprint = Fingerprints.of("TRANSFER",
                List.of(batchNo, request.fromHolder(), request.toHolder()));
        Optional<RequestLogRow> replay = beginRequest(request.requestId(), "TRANSFER", fingerprint);
        if (replay.isPresent()) {
            return readReplay(replay.get(), TransferResponse.class);
        }
        TransferResponse response = doCreateTransfer(batchNo, request);
        repository.completeRequest(request.requestId(), writeJson(response));
        return response;
    }

    private TransferResponse doCreateTransfer(String batchNo, TransferRequest request) {
        TimberBatch batch = repository.lockBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchNo));
        if (!batch.leaf()) {
            throw ApiException.unprocessable("BATCH_ALREADY_SPLIT", "已拆分父批次仅保留来源节点，不得直接移交");
        }
        if (repository.findPendingTransferForBatch(batch.id()).isPresent()) {
            throw ApiException.conflict("TRANSFER_PENDING", "批次存在待签收移交，不能重复申请: " + batchNo);
        }
        if (!batch.holder().equals(request.fromHolder())) {
            throw ApiException.conflict("HOLDER_MISMATCH",
                    "无权移交：当前持有人为" + batch.holder() + "，非" + request.fromHolder());
        }
        if (request.fromHolder().equals(request.toHolder())) {
            throw ApiException.unprocessable("SAME_HOLDER", "移交方与接收方不能相同");
        }
        TransferOrder order = repository.insertTransfer(batch.id(), request.fromHolder(),
                request.toHolder(), request.requestId());
        return new TransferResponse(order.id(), batchNo, order.fromHolder(), order.toHolder(), order.status());
    }
    /** 签收或拒绝移交：签收后持有人变更，拒绝保留原因且不移交。 */
    @Transactional
    public SignResponse sign(long transferId, SignRequest request) {
        String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
        if (!decision.equals("ACCEPT") && !decision.equals("REJECT")) {
            throw ApiException.unprocessable("INVALID_DECISION", "decision必须为ACCEPT或REJECT");
        }
        String fingerprint = Fingerprints.of("SIGN",
                List.of(String.valueOf(transferId), decision, request.reason() == null ? "" : request.reason()));
        Optional<RequestLogRow> replay = beginRequest(request.requestId(), "SIGN", fingerprint);
        if (replay.isPresent()) {
            return readReplay(replay.get(), SignResponse.class);
        }
        SignResponse response = doSign(transferId, decision, request.reason());
        repository.completeRequest(request.requestId(), writeJson(response));
        return response;
    }

    private SignResponse doSign(long transferId, String decision, String reason) {
        TransferOrder order = repository.lockTransferById(transferId)
                .orElseThrow(() -> ApiException.notFound("TRANSFER_NOT_FOUND", "移交单不存在: " + transferId));
        if (!TransferOrder.PENDING.equals(order.status())) {
            throw ApiException.conflict("TRANSFER_ALREADY_DECIDED", "移交单已签收或已拒绝: " + transferId);
        }
        TimberBatch batch = repository.findBatchById(order.batchId())
                .orElseThrow(() -> new IllegalStateException("移交单关联批次缺失: " + order.batchId()));
        if (decision.equals("ACCEPT")) {
            repository.updateBatchHolder(batch.id(), order.toHolder());
            repository.decideTransfer(order.id(), TransferOrder.ACCEPTED, null);
            return new SignResponse(order.id(), batch.batchNo(), TransferOrder.ACCEPTED,
                    order.toHolder(), null);
        }
        if (reason == null || reason.isBlank()) {
            throw ApiException.unprocessable("REJECT_REASON_REQUIRED", "拒绝签收时必须填写拒绝原因");
        }
        repository.decideTransfer(order.id(), TransferOrder.REJECTED, reason);
        return new SignResponse(order.id(), batch.batchNo(), TransferOrder.REJECTED,
                batch.holder(), reason);
    }

    /** 按批次编号查询完整祖先链、重量与当前持有人。 */
    @Transactional(readOnly = true)
    public TraceResponse trace(String batchNo) {
        TimberBatch batch = repository.findBatchByNo(batchNo)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "批次不存在: " + batchNo));
        List<TraceNode> chain = new ArrayList<>();
        TimberBatch current = batch;
        while (true) {
            chain.add(0, new TraceNode(current.batchNo(), current.weightKg(), current.holder(), current.leaf()));
            if (current.parentId() == null) {
                break;
            }
            long parentId = current.parentId();
            current = repository.findBatchById(parentId)
                    .orElseThrow(() -> new IllegalStateException("父批次缺失: " + parentId));
        }
        return new TraceResponse(batch.batchNo(), batch.voucherNo(), batch.species(),
                batch.weightKg(), batch.holder(), chain);
    }

    /**
     * 幂等请求入口：已存在同参请求返回原响应，异参抛409；
     * 不存在则先占键，业务失败回滚时键一并撤销，不会消耗请求键。
     */
    private Optional<RequestLogRow> beginRequest(String requestId, String operation, String fingerprint) {
        while (true) {
            Optional<RequestLogRow> existing = repository.findRequest(requestId);
            if (existing.isPresent()) {
                RequestLogRow row = existing.get();
                if (!row.operation().equals(operation) || !row.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("REQUEST_CONFLICT", "requestId已使用且参数不一致: " + requestId);
                }
                if (row.responseBody() == null) {
                    throw ApiException.conflict("REQUEST_IN_FLIGHT", "相同requestId的请求正在处理中: " + requestId);
                }
                return Optional.of(row);
            }
            try {
                repository.insertRequest(requestId, operation, fingerprint);
                return Optional.empty();
            } catch (DuplicateKeyException e) {
                // 并发同键插入：另一事务已提交，重读记录后按重放或冲突处理
            }
        }
    }

    private BatchView toView(TimberBatch batch) {
        return new BatchView(batch.batchNo(), batch.weightKg(), batch.holder(),
                batch.voucherNo(), batch.species(), batch.leaf());
    }

    private String writeJson(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T readReplay(RequestLogRow row, Class<T> type) {
        try {
            return objectMapper.readValue(row.responseBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败: " + row.requestId(), e);
        }
    }
}