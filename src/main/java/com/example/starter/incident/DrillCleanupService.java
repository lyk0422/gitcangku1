package com.example.starter.incident;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.example.starter.incident.dto.Requests.CleanupRequest;
import com.example.starter.incident.dto.Responses.CleanupHistoryItem;
import com.example.starter.incident.dto.Responses.CleanupView;
import com.example.starter.incident.dto.Responses.IncidentView;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 演练批次批量清理服务。
 * 清理与演练写操作均先锁定 drill_batches 批次行，按事务提交顺序裁决：
 * 清理先提交则批次成为 CLEANED 墓碑，后续演练写入返回 404；
 * 演练写入先提交则清理在取得批次锁后重新校验全部事件终态，未终结返回 422。
 * 终态校验与删除在同一事务内完成，保证原子性，不影响其他批次与真实域。
 */
@Service
public class DrillCleanupService {

    private final IncidentRepository incidents;

    public DrillCleanupService(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    /**
     * 提交批次清理。cleanupKey 同键同参重放首次结果，同键异参 409，失败不占键。
     * 批次内任一事件未终结（非 RESOLVED/CLOSED/CANCELLED）则整批 422 并列出未终结事件。
     */
    @Transactional
    public CleanupView cleanup(String actor, CleanupRequest req) {
        String cleanupKey = requireText(req.cleanupKey(), "cleanupKey");
        String batchKey = requireText(req.batchKey(), "batchKey");

        var prior = incidents.findCleanup(cleanupKey);
        if (prior.isPresent()) {
            return replay(prior.get(), batchKey);
        }

        // 先锁批次行：与演练写入串行化。
        DrillBatch batch = incidents.lockBatch(batchKey)
                .orElseThrow(() -> ApiException.notFound("演练批次不存在: " + batchKey));
        // 取得批次锁后复查幂等键：并发同键请求在此重放首个已提交结果，而非看到 404。
        var raced = incidents.findCleanup(cleanupKey);
        if (raced.isPresent()) {
            return replay(raced.get(), batchKey);
        }
        if (batch.status() == DrillBatchStatus.CLEANED) {
            throw ApiException.notFound("演练批次已清理: " + batchKey);
        }

        // 取得批次锁后重新读取并锁定全部事件（写入先提交的情形在此重新校验）。
        List<Incident> locked = incidents.lockIncidentsByBatch(batchKey);
        List<Incident> unfinished = locked.stream()
                .filter(i -> !i.status().isTerminal())
                .toList();
        if (!unfinished.isEmpty()) {
            String list = unfinished.stream()
                    .map(i -> i.incidentKey() + "(" + i.status() + ")")
                    .toList().toString();
            throw ApiException.batchNotTerminal("批次存在未终结事件，整批不可清理: " + list);
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<Long> ids = locked.stream().map(Incident::id).toList();
        incidents.deleteBatchIncidents(batchKey, ids);
        incidents.markBatchCleaned(batchKey, now);
        try {
            incidents.insertCleanup(cleanupKey, batchKey, ids.size(), actor, now);
        } catch (DuplicateKeyException e) {
            // 并发同键：事务回滚由唯一约束保证不产生双重删除；交重放逻辑处理。
            throw ApiException.conflict("cleanupKey 处理冲突: " + cleanupKey);
        }
        return new CleanupView(cleanupKey, batchKey, ids.size(), "CLEANED", now);
    }

    /**
     * 查询演练批次事件清单（按批次标识）。批次已清理则返回空清单，
     * 批次不存在返回 404。
     */
    @Transactional(readOnly = true)
    public List<IncidentView> listBatchIncidents(String batchKey) {
        requireText(batchKey, "batchKey");
        if (incidents.findBatch(batchKey).isEmpty()) {
            throw ApiException.notFound("演练批次不存在: " + batchKey);
        }
        return incidents.listIncidentsByBatch(batchKey).stream()
                .map(i -> new IncidentView(i.domain().name(), i.incidentKey(), i.drillKey(),
                        i.drillBatch(), i.severity(), i.summary(), i.reporter(), i.status().name(),
                        i.commander(), null, i.createdAt(), i.updatedAt()))
                .toList();
    }

    /**
     * 查询清理历史；batchKey 非空时只看该批次，为空时返回全部演练清理历史。
     */
    @Transactional(readOnly = true)
    public List<CleanupHistoryItem> listHistory(String batchKey) {
        String filter = (batchKey == null || batchKey.isBlank()) ? null : batchKey.strip();
        return incidents.listCleanups(filter).stream()
                .map(c -> new CleanupHistoryItem(c.cleanupKey(), c.batchKey(), c.deletedIncidents(),
                        c.actor(), c.createdAt()))
                .toList();
    }

    private CleanupView replay(CleanupRecord record, String batchKey) {
        if (!record.batchKey().equals(batchKey)) {
            throw ApiException.conflict("cleanupKey 已被不同参数的请求使用: " + record.cleanupKey());
        }
        return new CleanupView(record.cleanupKey(), record.batchKey(), record.deletedIncidents(),
                "CLEANED", record.createdAt());
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest(field + " 不能为空");
        }
        return value.strip();
    }
}
