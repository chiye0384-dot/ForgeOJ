package com.forgeoj.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class M0OutputComparatorTests {

    private final M0OutputComparator comparator = new M0OutputComparator();

    @Test
    void normalizesLineEndingsTrailingWhitespaceAndFinalBlankLines() {
        assertThat(comparator.matches("1  \r\n2\t\r\n\r\n", "1\n2\n")).isTrue();
    }

    @Test
    void keepsOtherContentStrict() {
        assertThat(comparator.matches("1 2\n", "1  2\n")).isFalse();
        assertThat(comparator.matches("ABC\n", "abc\n")).isFalse();
    }
}
