package com.example.starter.blind.service;

import com.example.starter.blind.ApiException;
import com.example.starter.blind.Clock;
import com.example.starter.blind.dto.AnalysisView;
import com.example.starter.blind.repo.AllocationRepository;
import com.example.starter.blind.repo.AllocationRepository.AllocationRow;
import com.example.starter.blind.repo.AnalysisRepository;
import com.example.starter.blind.repo.AnalysisRepository.AnalysisRow;
import com.example.starter.blind.repo.ExperimentRepository;
import com.example.starter.blind.repo.ExperimentRepository.ExperimentRow;
import com.example.starter.blind.repo.ExperimentRepository.SeatRow;
import com.example.starter.blind.repo.ResultRepository;
import com.example.starter.blind.repo.ResultRepository.ResultRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 受限精确置换分析：
 * 实验关闭后 REVIEWER 引用 4~8 个不同未退组参与者及固定结果版本；
 * 分组映射取自数据库，观察统计量为两组均值差的绝对值；
 * 枚举保持 A 人数不变的全部分组，输出精确 k/n 与匿名人数。
 * 个体值与映射只冻结在库内 snapshot，不进入响应与日志。
 * 全程整数交叉相乘比较，无随机采样、无浮点比较。
 */
@Service
public class AnalysisService {

    static final int MIN_REFERENCES = 4;
    static final int MAX_REFERENCES = 8;
    static final int MIN_PER_GROUP = 2;

    private final ExperimentRepository experimentRepository;
    private final AllocationRepository allocationRepository;
    private final ResultRepository resultRepository;
    private final AnalysisRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AnalysisService(ExperimentRepository experimentRepository,
                           AllocationRepository allocationRepository,
                           ResultRepository resultRepository,
                           AnalysisRepository analysisRepository,
                           ObjectMapper objectMapper,
                           Clock clock) {
        this.experimentRepository = experimentRepository;
        this.allocationRepository = allocationRepository;
        this.resultRepository = resultRepository;
        this.analysisRepository = analysisRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 执行分析并冻结内部依据；失败抛业务异常，不落任何统计记录。
     */
    @Transactional
    public AnalysisView analyze(String experimentId, List<String> participantIds,
                                int resultVersion, String analystActor) {
        List<String> ids = validateReferences(participantIds);
        // 行级锁串行化：与关闭、结果提交、退组互斥，最终集合由串行顺序决定。
        ExperimentRow experiment = experimentRepository.lockById(experimentId);
        if (experiment == null) {
            throw ApiException.notFound("实验不存在: " + experimentId);
        }
        if (!"CLOSED".equals(experiment.status())) {
            throw ApiException.conflict("实验关闭后才能发起受限统计分析");
        }
        int total = ids.size();
        long[] values = new long[total];
        boolean[] isA = new boolean[total];
        List<Map<String, Object>> basis = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            String pid = ids.get(i);
            AllocationRow allocation =
                    allocationRepository.findByExperimentAndParticipant(experimentId, pid);
            if (allocation == null) {
                throw ApiException.unprocessable("引用参与者未在该实验登记: " + pid);
            }
            if ("WITHDRAWN".equals(allocation.status())) {
                throw ApiException.unprocessable("引用参与者已退组: " + pid);
            }
            ResultRow result = resultRepository.findVersion(experimentId, pid, resultVersion);
            if (result == null) {
                ResultRow current = resultRepository.findCurrent(experimentId, pid);
                if (current == null) {
                    throw ApiException.unprocessable("引用参与者缺少结果值: " + pid);
                }
                throw ApiException.conflict("引用结果版本超出已有版本: " + pid);
            }
            SeatRow seat = experimentRepository.findSeat(experimentId,
                    allocation.blockNo(), allocation.seatNo());
            if (seat == null) {
                throw new IllegalStateException("席位映射缺失，数据不一致");
            }
            values[i] = result.resultValue();
            isA[i] = "A".equals(seat.treatment());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("participantId", pid);
            entry.put("treatment", seat.treatment());
            entry.put("value", result.resultValue());
            basis.add(entry);
        }
        int aCount = 0;
        for (boolean a : isA) {
            aCount += a ? 1 : 0;
        }
        int bCount = total - aCount;
        if (aCount < MIN_PER_GROUP || bCount < MIN_PER_GROUP) {
            throw ApiException.unprocessable("每个处理组至少 " + MIN_PER_GROUP + " 人");
        }
        long[] counts = exactPermutation(values, isA, aCount);
        long now = clock.nowMillis();
        String analysisId = "AN-" + UUID.randomUUID().toString().replace("-", "");
        analysisRepository.insert(new AnalysisRow(analysisId, experimentId, resultVersion,
                total, aCount, bCount, counts[0], counts[1],
                toSnapshot(experimentId, resultVersion, basis), analystActor, now));
        return new AnalysisView(analysisId, experimentId, resultVersion, total,
                aCount, bCount, counts[0], counts[1], now);
    }

    /**
     * 历史查询：返回冻结的统计结果，仍只含 k/n 与匿名人数。
     */
    public AnalysisView getAnalysis(String analysisId) {
        AnalysisRow row = analysisRepository.findById(analysisId);
        if (row == null) {
            throw ApiException.notFound("分析不存在: " + analysisId);
        }
        return new AnalysisView(row.id(), row.experimentId(), row.resultVersion(),
                row.participantCount(), row.groupACount(), row.groupBCount(),
                row.favorableCount(), row.totalCount(), row.createdAt());
    }

    private List<String> validateReferences(List<String> participantIds) {
        if (participantIds == null
                || participantIds.size() < MIN_REFERENCES
                || participantIds.size() > MAX_REFERENCES) {
            throw ApiException.unprocessable(
                    "引用参与者人数必须在 " + MIN_REFERENCES + "~" + MAX_REFERENCES + " 之间");
        }
        List<String> ids = participantIds.stream()
                .map(id -> id == null ? "" : id.trim())
                .sorted()
                .distinct()
                .toList();
        if (ids.stream().anyMatch(String::isBlank)) {
            throw ApiException.unprocessable("引用参与者编号不能为空");
        }
        if (ids.size() != participantIds.size()) {
            throw ApiException.unprocessable("引用参与者必须互不相同");
        }
        return ids;
    }

    /**
     * 精确置换：统计量为 |meanA - meanB|，两边同乘 aCount*bCount 后比较分子
     * |sumA*bCount - sumB*aCount|，避免浮点。返回 {k, n}。
     */
    private long[] exactPermutation(long[] values, boolean[] isA, int aCount) {
        int total = values.length;
        int bCount = total - aCount;
        long sumA = 0;
        long sumAll = 0;
        for (int i = 0; i < total; i++) {
            sumAll += values[i];
            sumA += isA[i] ? values[i] : 0;
        }
        long observed = Math.abs(sumA * bCount - (sumAll - sumA) * aCount);
        long favorable = 0;
        long combinations = 0;
        // 位掩码枚举全部 C(total, aCount) 种 A 组人选；total <= 8，规模有限。
        for (int mask = 0; mask < (1 << total); mask++) {
            if (Integer.bitCount(mask) != aCount) {
                continue;
            }
            combinations++;
            long groupSum = 0;
            for (int i = 0; i < total; i++) {
                if ((mask & (1 << i)) != 0) {
                    groupSum += values[i];
                }
            }
            long statistic = Math.abs(groupSum * bCount - (sumAll - groupSum) * aCount);
            if (statistic >= observed) {
                favorable++;
            }
        }
        return new long[]{favorable, combinations};
    }

    /** 完整内部依据（含个体映射与取值），仅落库 snapshot，不进入响应。 */
    private String toSnapshot(String experimentId, int resultVersion,
                              List<Map<String, Object>> basis) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("experimentId", experimentId);
        snapshot.put("resultVersion", resultVersion);
        snapshot.put("references", basis);
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("分析依据无法序列化", e);
        }
    }
}
