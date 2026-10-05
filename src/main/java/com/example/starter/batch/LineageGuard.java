package com.example.starter.batch;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 血缘门禁：任一级祖先被直接召回（RECALLED）时，后代批次禁止新增业务写入。
 * 供批次服务与配方称量服务共用，判定逻辑保持一致。
 */
@Component
public class LineageGuard {

    private final BatchRepository repo;

    public LineageGuard(BatchRepository repo) {
        this.repo = repo;
    }

    /**
     * 若任一级祖先已被召回则抛 422：后代批次禁止新增检验、批准、拆分与称量写入。
     */
    public void assertNoRecalledAncestor(String batchKey) {
        Optional<String> recalled = recalledAncestor(batchKey);
        if (recalled.isPresent()) {
            throw ApiException.unprocessable(
                    "祖先批次 " + recalled.get() + " 已召回，禁止新增检验、批准、拆分和称量");
        }
    }

    /**
     * 沿父链向上查找最近的被直接召回（RECALLED）祖先；批次自身召回不算祖先召回。
     */
    public Optional<String> recalledAncestor(String batchKey) {
        Map<String, String> parentOf = childToParent();
        Set<String> recalled = new HashSet<>(repo.findRecalledKeys());
        String current = batchKey;
        while (parentOf.containsKey(current)) {
            current = parentOf.get(current);
            if (recalled.contains(current)) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
    }

    /**
     * 全部血缘边的 子批→父批 映射；关系不可改写，只增不改。
     */
    private Map<String, String> childToParent() {
        Map<String, String> parentOf = new HashMap<>();
        for (BatchRepository.LineageRow row : repo.findAllLineage()) {
            parentOf.put(row.childKey(), row.parentKey());
        }
        return parentOf;
    }
}