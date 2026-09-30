package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批次血缘视图：合并拆分边（batch_lineage，一子一父）与合批边（batch_merge_parent，一子可两父），
 * 供祖先召回判定、可用性过滤与祖先/后代查询统一使用。边只增不改。
 */
@Component
public class LineageView {

    private final BatchRepository batchRepository;
    private final StorageRepository storageRepository;

    public LineageView(BatchRepository batchRepository, StorageRepository storageRepository) {
        this.batchRepository = batchRepository;
        this.storageRepository = storageRepository;
    }

    /**
     * 子批 → 全部父批（拆分至多 1 个，合批 2 个），边按底层插入顺序保留。
     */
    public Map<String, List<String>> parentsOf() {
        Map<String, List<String>> parents = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepository.findAllLineage()) {
            parents.computeIfAbsent(row.childKey(), k -> new ArrayList<>()).add(row.parentKey());
        }
        for (StorageRepository.ParentChild edge : storageRepository.findAllMergeEdges()) {
            List<String> ps = parents.computeIfAbsent(edge.childKey(), k -> new ArrayList<>());
            if (!ps.contains(edge.parentKey())) {
                ps.add(edge.parentKey());
            }
        }
        return parents;
    }

    /**
     * 父批 → 全部直接子批（含拆分子批与作为合批产物的子批）。
     */
    public Map<String, List<String>> childrenOf() {
        Map<String, List<String>> children = new HashMap<>();
        for (BatchRepository.LineageRow row : batchRepository.findAllLineage()) {
            children.computeIfAbsent(row.parentKey(), k -> new ArrayList<>()).add(row.childKey());
        }
        for (StorageRepository.ParentChild edge : storageRepository.findAllMergeEdges()) {
            List<String> cs = children.computeIfAbsent(edge.parentKey(), k -> new ArrayList<>());
            if (!cs.contains(edge.childKey())) {
                cs.add(edge.childKey());
            }
        }
        return children;
    }

    /**
     * 沿父链（合批子批有多个父批）向上查找最近的被直接召回（RECALLED）祖先；自身召回不算。
     */
    public List<String> ancestorsOf(String batchKey) {
        Map<String, List<String>> parents = parentsOf();
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(batchKey);
        seen.add(batchKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String parent : parents.getOrDefault(current, List.of())) {
                if (seen.add(parent)) {
                    result.add(parent);
                    queue.add(parent);
                }
            }
        }
        return result;
    }

    /**
     * 某批次的全部后代业务键（不含自身），广度优先展开，去重保序。
     */
    public List<String> descendantsOf(String rootKey) {
        Map<String, List<String>> children = childrenOf();
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootKey);
        seen.add(rootKey);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String child : children.getOrDefault(current, List.of())) {
                if (seen.add(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }
}
