package com.example.starter.custody;

import com.example.starter.custody.CustodyDtos.BatchView;
import com.example.starter.custody.CustodyDtos.ChildSpec;
import com.example.starter.custody.CustodyDtos.SignoffRequest;
import com.example.starter.custody.CustodyDtos.SplitRequest;
import com.example.starter.custody.CustodyDtos.SplitResponse;
import com.example.starter.custody.CustodyDtos.SourceRequest;
import com.example.starter.custody.CustodyDtos.TraceNode;
import com.example.starter.custody.CustodyDtos.TraceResponse;
import com.example.starter.custody.CustodyDtos.TransferRequest;
import com.example.starter.custody.CustodyDtos.TransferView;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 木材监管链业务:来源登记、批次拆分、移交签收、追溯查询。
 * 拆分与移交通过批次行锁互斥,同一叶子批次并发时只有一个操作成功。
 */
@Service
public class CustodyService {

    private final BatchRepository batches;
    private final TransferRepository transfers;
    private final IdempotencyService idempotency;
    private final Clock clock;

    public CustodyService(BatchRepository batches, TransferRepository transfers,
                          IdempotencyService idempotency, Clock clock) {
        this.batches = batches;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    /** 登记来源批次,生成根节点。 */
    @Transactional
    public BatchView registerSource(SourceRequest request) {
        if (batches.existsByCode(request.batchCode())) {
            throw ApiException.conflict("批次编号已存在: " + request.batchCode());
        }
        BatchRecord root = new BatchRecord(0L, request.batchCode(), request.voucherNo(),
                request.species(), request.weightKg(), null, request.holder(), BatchStatus.ACTIVE);
        batches.insert(root);
        return BatchView.of(batches.findByCode(request.batchCode()).orElseThrow());
    }

    /** 拆分:子批次重量均为正且总和等于父批次当前可拆重量,任一校验失败整体回滚。 */
    @Transactional
    public SplitResponse split(String parentCode, SplitRequest request) {
        String params = parentCode + "|" + request.children().stream()
                .map(c -> c.batchCode() + ":" + c.weightKg() + ":" + String.valueOf(c.voucherNo()))
                .sorted().collect(Collectors.joining(","));
        return idempotency.execute(request.requestId(), "SPLIT", params, SplitResponse.class,
                () -> doSplit(parentCode, request));
    }

    private SplitResponse doSplit(String parentCode, SplitRequest request) {
        BatchRecord parent = batches.lockByCode(parentCode)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + parentCode));
        if (parent.status() != BatchStatus.ACTIVE) {
            throw ApiException.conflict("批次已拆分,不可再次拆分: " + parentCode);
        }
        if (transfers.existsPending(parent.id())) {
            throw ApiException.conflict("批次存在待签收移交,不可拆分: " + parentCode);
        }
        Set<String> codes = new HashSet<>();
        int sum = 0;
        for (ChildSpec child : request.children()) {
            if (!codes.add(child.batchCode())) {
                throw ApiException.unprocessable("子批次编号重复: " + child.batchCode());
            }
            if (child.voucherNo() != null && !child.voucherNo().equals(parent.voucherNo())) {
                throw ApiException.unprocessable("子批次凭证与父批次不一致,禁止跨来源合并: "
                        + child.batchCode());
            }
            sum += child.weightKg();
        }
        if (sum != parent.weightKg()) {
            throw ApiException.unprocessable("子批次重量之和" + sum
                    + "不等于父批次可拆重量" + parent.weightKg());
        }
        for (ChildSpec child : request.children()) {
            if (batches.existsByCode(child.batchCode())) {
                throw ApiException.conflict("批次编号已存在: " + child.batchCode());
            }
        }
        List<BatchView> children = new ArrayList<>();
        for (ChildSpec child : request.children()) {
            batches.insert(new BatchRecord(0L, child.batchCode(), parent.voucherNo(),
                    parent.species(), child.weightKg(), parent.id(), parent.holder(),
                    BatchStatus.ACTIVE));
            children.add(BatchView.of(batches.findByCode(child.batchCode()).orElseThrow()));
        }
        batches.markSplit(parent.id());
        return new SplitResponse(parentCode, children);
    }

    /** 发起移交:仅未移交、未拆分的叶子批次,且fromHolder必须为当前持有人。 */
    @Transactional
    public TransferView createTransfer(String batchCode, TransferRequest request) {
        BatchRecord batch = batches.lockByCode(batchCode)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchCode));
        if (batch.status() != BatchStatus.ACTIVE) {
            throw ApiException.conflict("批次已拆分,仅作来源节点,不可移交: " + batchCode);
        }
        if (!batch.holder().equals(request.fromHolder())) {
            throw ApiException.unprocessable("无权移交:当前持有人为" + batch.holder());
        }
        if (transfers.existsPendingOrAccepted(batch.id())) {
            throw ApiException.conflict("批次已移交或移交待签收: " + batchCode);
        }
        long id = transfers.insert(batch.id(), request.fromHolder(), request.toHolder());
        return toView(transfers.findById(id).orElseThrow());
    }

    /** 签收或拒绝:拒绝保持原持有人并记录原因;requestId同参重放、异参409。 */
    @Transactional
    public TransferView signoff(long transferId, SignoffRequest request) {
        String params = transferId + "|" + request.decision() + "|"
                + String.valueOf(request.rejectReason());
        return idempotency.execute(request.requestId(), "SIGNOFF", params, TransferView.class,
                () -> doSignoff(transferId, request));
    }

    private TransferView doSignoff(long transferId, SignoffRequest request) {
        TransferRecord transfer = transfers.lockById(transferId)
                .orElseThrow(() -> ApiException.notFound("移交申请不存在: " + transferId));
        if (transfer.status() != TransferStatus.PENDING) {
            throw ApiException.conflict("移交申请已决定,不可重复签收: " + transferId);
        }
        String decision = request.decision().toUpperCase();
        Instant now = Instant.now(clock);
        switch (decision) {
            case "ACCEPT" -> {
                transfers.decide(transferId, TransferStatus.ACCEPTED, null, now);
                batches.updateHolder(transfer.batchId(), transfer.toHolder());
            }
            case "REJECT" -> {
                if (request.rejectReason() == null || request.rejectReason().isBlank()) {
                    throw ApiException.unprocessable("拒绝签收必须填写拒绝原因");
                }
                transfers.decide(transferId, TransferStatus.REJECTED, request.rejectReason(), now);
            }
            default -> throw ApiException.unprocessable("decision必须为ACCEPT或REJECT");
        }
        return toView(transfers.findById(transferId).orElseThrow());
    }

    /** 追溯:返回完整祖先链(来源到当前批次)、重量与当前持有人。 */
    @Transactional(readOnly = true)
    public TraceResponse trace(String batchCode) {
        BatchRecord current = batches.findByCode(batchCode)
                .orElseThrow(() -> ApiException.notFound("批次不存在: " + batchCode));
        Deque<TraceNode> chain = new ArrayDeque<>();
        BatchRecord node = current;
        while (node != null) {
            chain.addFirst(new TraceNode(node.batchCode(), node.voucherNo(), node.species(),
                    node.weightKg(), node.status().name()));
            node = node.parentId() == null ? null : batches.findById(node.parentId()).orElse(null);
        }
        return new TraceResponse(current.batchCode(), current.weightKg(), current.holder(),
                List.copyOf(chain));
    }

    private TransferView toView(TransferRecord transfer) {
        String code = batches.findById(transfer.batchId()).orElseThrow().batchCode();
        return new TransferView(transfer.id(), code, transfer.fromHolder(), transfer.toHolder(),
                transfer.status().name(), transfer.rejectReason());
    }
}