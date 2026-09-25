package com.example.starter.incident;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 疏散门禁裁决组件：在同一事件写事务（已持事件行锁）内，按服务端当前 UTC 时刻
 * 先结束裁决（到期区域恢复/改挂阻断任务、区域置 ENDED），再生效裁决
 * （有效区域把未开始命中且无对应版本豁免的任务固化为 EVACUATION_BLOCKED），
 * 随后为创建/派工/开始/完成/撤离提供“命中但缺少豁免”的区域判定。
 *
 * <p>所有规则仅依赖已提交数据与调用方传入的时刻，区域、豁免、派工、开始、完成
 * 因而按事务提交顺序串行裁决。
 */
@Component
public class EvacuationGate {

    private final EvacuationRepository evacuation;
    private final IncidentTaskRepository tasks;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EvacuationGate(EvacuationRepository evacuation, IncidentTaskRepository tasks,
                          ObjectMapper objectMapper, Clock clock) {
        this.evacuation = evacuation;
        this.tasks = tasks;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * 阻断快照（落库与视图共用）。
     */
    public record ZoneSnapshot(String zoneKey, int version, String riskLevel, List<String> grids,
                               Instant effectiveFrom, Instant effectiveTo) {
    }

    /**
     * 每次写操作的门禁前裁决：结束到期区域并恢复/改挂阻断任务，随后让新生效区域阻断命中任务。
     */
    public void evaluate(Incident incident) {
        Instant now = now();
        List<EvacuationZone> registered = evacuation.listActiveZones(incident.id());

        // 结束裁决：窗口到期的区域恢复其阻断任务；仍被其他有效区域命中的任务改挂到该区域。
        for (EvacuationZone zone : registered) {
            if (!zone.windowExpiredAt(now)) {
                continue;
            }
            for (IncidentTask blocked : tasks.listBlockedByZone(zone.id())) {
                List<EvacuationZone> others = violatingZones(incident.id(), blocked.taskKey(),
                        blocked.workGrid(), now, zone.id());
                if (others.isEmpty()) {
                    tasks.reopenBlocked(blocked.id(), now);
                } else {
                    EvacuationZone next = others.get(0);
                    tasks.repointBlocked(blocked.id(), next.id(), snapshotJson(next), now);
                }
            }
            evacuation.markEnded(zone.id(), now);
        }

        // 生效裁决：当前有效的区域阻断未开始（OPEN/DISPATCHED）命中且无豁免任务。
        for (EvacuationZone zone : evacuation.listActiveZones(incident.id())) {
            if (!zone.effectiveAt(now)) {
                continue;
            }
            for (IncidentTask task : tasks.listByIncident(incident.id())) {
                boolean notStarted = task.status() == TaskStatus.OPEN
                        || task.status() == TaskStatus.DISPATCHED;
                if (!notStarted || !zone.containsGrid(task.workGrid())) {
                    continue;
                }
                if (hasValidExemption(zone, task.taskKey())) {
                    continue;
                }
                int blocked = tasks.markBlockedIfNotStarted(task.id(), zone.id(),
                        snapshotJson(zone), now);
                if (blocked == 1 && task.status() == TaskStatus.DISPATCHED) {
                    // 已派工任务被阻断：派工一并回退（删除租约），恢复 OPEN 后可重新派工。
                    tasks.deleteLease(task.id());
                }
            }
        }
    }

    /**
     * 当前时刻有效且命中作业网格、但任务缺少该区域版本豁免的全部区域（按版本顺序）。
     * 创建/派工/开始/完成门禁共用；excludeZoneId 用于结束裁决改挂时排除到期区域自身。
     */
    public List<EvacuationZone> violatingZones(long incidentId, String taskKey, String workGrid,
                                               Instant now, long excludeZoneId) {
        List<EvacuationZone> violating = new ArrayList<>();
        for (EvacuationZone zone : evacuation.listActiveZones(incidentId)) {
            if (zone.id() == excludeZoneId || !zone.effectiveAt(now)) {
                continue;
            }
            if (zone.containsGrid(workGrid) && !hasValidExemption(zone, taskKey)) {
                violating.add(zone);
            }
        }
        return violating;
    }

    /**
     * 以注入时钟当前时刻计算违规区域。
     */
    public List<EvacuationZone> violatingZonesNow(long incidentId, String taskKey, String workGrid) {
        return violatingZones(incidentId, taskKey, workGrid, now(), -1L);
    }

    /**
     * 任务是否持有区域当前版本的有效豁免（豁免版本须等于区域登记版本）。
     */
    public boolean hasValidExemption(EvacuationZone zone, String taskKey) {
        return evacuation.findExemption(zone.id(), taskKey)
                .filter(e -> e.version() == zone.version())
                .isPresent();
    }

    /**
     * 区域当前是否有效（按注入时钟，只读计算）。
     */
    public boolean effectiveNow(EvacuationZone zone) {
        return zone.effectiveAt(now());
    }

    /**
     * 序列化阻断快照为 JSON。
     */
    public String snapshotJson(EvacuationZone zone) {
        try {
            return objectMapper.writeValueAsString(new ZoneSnapshot(zone.zoneKey(), zone.version(),
                    zone.riskLevel().name(), zone.grids(), zone.effectiveFrom(), zone.effectiveTo()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("区域快照序列化失败", e);
        }
    }

    /**
     * 反序列化任务固化的阻断快照 JSON。
     */
    public ZoneSnapshot readSnapshot(String json) {
        try {
            return objectMapper.readValue(json, ZoneSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("区域快照反序列化失败", e);
        }
    }
}
