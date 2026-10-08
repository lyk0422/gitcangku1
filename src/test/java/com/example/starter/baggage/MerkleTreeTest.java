package com.example.starter.baggage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.starter.baggage.BaggageDtos.ProofNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Merkle 清单摘要纯单元测试：摘要二进制格式、UTF-8 字节无符号排序、
 * 奇数复制末项、路径长度与方向核验及篡改检测。
 */
class MerkleTreeTest {

    @Test
    void leafHashUsesDomainByteLengthAndTagBytes() throws Exception {
        byte[] tag = "A".getBytes(StandardCharsets.UTF_8);
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update((byte) 0x00);
        expected.update(new byte[]{0, 0, 0, (byte) tag.length});
        expected.update(tag);
        assertThat(MerkleTree.leafHash("A")).isEqualTo(expected.digest());
    }

    @Test
    void parentHashUsesDomainAndConcatenatedDigests() throws Exception {
        byte[] left = MerkleTree.leafHash("A");
        byte[] right = MerkleTree.leafHash("B");
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        expected.update((byte) 0x01);
        expected.update(left);
        expected.update(right);
        assertThat(MerkleTree.parentHash(left, right)).isEqualTo(expected.digest());
    }

    @Test
    void bagTagOrderIsUnsignedUtf8ByteOrderNotStringOrder() {
        // '豈'(U+F900) 的 UTF-16 码元大于 '😀'(U+1F600) 的高位代理项，
        // 但 UTF-8 首字节 0xEF 小于 0xF0，两种次序相反
        assertThat("BAG豈".compareTo("BAG😀")).isGreaterThan(0);
        List<String> tags = new ArrayList<>(List.of("BAG😀", "BAG豈"));
        tags.sort(MerkleTree.BAG_TAG_ORDER);
        assertThat(tags).containsExactly("BAG豈", "BAG😀");
    }

    @Test
    void rootDuplicatesLastNodeOnOddLevelsAndRejectsEmpty() {
        byte[] hA = MerkleTree.leafHash("A");
        byte[] hB = MerkleTree.leafHash("B");
        byte[] hC = MerkleTree.leafHash("C");
        byte[] expected = MerkleTree.parentHash(
                MerkleTree.parentHash(hA, hB), MerkleTree.parentHash(hC, hC));
        assertThat(MerkleTree.root(List.of("A", "B", "C"))).isEqualTo(expected);
        assertThat(MerkleTree.root(List.of("A"))).isEqualTo(hA);
        assertThatThrownBy(() -> MerkleTree.root(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void depthMatchesLeafCount() {
        assertThat(MerkleTree.depth(1)).isZero();
        assertThat(MerkleTree.depth(2)).isEqualTo(1);
        assertThat(MerkleTree.depth(3)).isEqualTo(2);
        assertThat(MerkleTree.depth(4)).isEqualTo(2);
        assertThat(MerkleTree.depth(5)).isEqualTo(3);
        assertThatThrownBy(() -> MerkleTree.depth(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void proofRoundTripsAndDetectsTampering() {
        List<String> tags = List.of("A", "B", "C");
        byte[] root = MerkleTree.root(tags);
        for (int i = 0; i < tags.size(); i++) {
            List<ProofNode> proof = MerkleTree.proof(tags, i);
            assertThat(proof).hasSize(MerkleTree.depth(3));
            assertThat(MerkleTree.verify(tags.get(i), i, 3, proof, root)).isTrue();
            // 袋号篡改
            assertThat(MerkleTree.verify("X", i, 3, proof, root)).isFalse();
            // 叶下标篡改
            assertThat(MerkleTree.verify(tags.get(i), (i + 1) % 3, 3, proof, root)).isFalse();
            // 路径长度不符（截断一层）
            assertThat(MerkleTree.verify(tags.get(i), i, 3,
                    proof.subList(0, proof.size() - 1), root)).isFalse();
            // 左右方向翻转
            List<ProofNode> flipped = proof.stream()
                    .map(node -> new ProofNode(
                            MerkleTree.SIBLING_LEFT.equals(node.position())
                                    ? MerkleTree.SIBLING_RIGHT : MerkleTree.SIBLING_LEFT,
                            node.hash()))
                    .toList();
            assertThat(MerkleTree.verify(tags.get(i), i, 3, flipped, root)).isFalse();
        }
        // C 的底层兄弟是 C 自身（奇数叶复制末项）
        List<ProofNode> proofC = MerkleTree.proof(tags, 2);
        assertThat(proofC.get(0).position()).isEqualTo(MerkleTree.SIBLING_RIGHT);
        assertThat(proofC.get(0).hash()).isEqualTo(MerkleTree.hex(MerkleTree.leafHash("C")));
        // 非法十六进制摘要
        assertThat(MerkleTree.verify("A", 0, 3, List.of(
                new ProofNode(MerkleTree.SIBLING_RIGHT, "zz"),
                new ProofNode(MerkleTree.SIBLING_RIGHT, "aa")), root)).isFalse();
    }
}
