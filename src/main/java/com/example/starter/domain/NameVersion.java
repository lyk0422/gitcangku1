package com.example.starter.domain;

/**
 * 制品名称与版本号的复合键，按名称字典序、版本号升序比较。
 */
public record NameVersion(String name, int version) implements Comparable<NameVersion> {

    @Override
    public int compareTo(NameVersion other) {
        int byName = this.name.compareTo(other.name);
        if (byName != 0) {
            return byName;
        }
        return Integer.compare(this.version, other.version);
    }
}
