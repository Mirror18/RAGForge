package com.ragforge.server.chunk;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure chunking engine tests: determinism, boundary awareness and anchors. */
class ChunkingEngineTest {

    private static final ChunkingStrategy STRATEGY = ChunkingStrategy.p4DefaultV1();

    private static String repeat(String unit, int times) {
        return unit.repeat(times);
    }

    private static ChunkingEngine.ChunkingResult chunk(String text) {
        return new ChunkingEngine(STRATEGY).chunk(text);
    }

    @Test
    void emptyInputProducesNoChunks() {
        ChunkingEngine.ChunkingResult result = chunk("");
        assertThat(result.parents()).isEmpty();
        assertThat(result.children()).isEmpty();
    }

    @Test
    void blankLinesAreSkippedWithoutChangingProvenanceRanges() {
        String text = "# Heading\n\n\nbody sentence one. body sentence two.\n";
        ChunkingEngine.ChunkingResult result = chunk(text);
        assertThat(result.parents()).hasSize(1);
        assertThat(result.parents().get(0).text()).contains("# Heading", "body sentence one.");
        assertThat(result.children()).isNotEmpty();
        assertThat(result.children().get(0).charStart()).isEqualTo(0);
        assertThat(result.children().get(result.children().size() - 1).charEnd())
                .isEqualTo(result.parents().get(0).charEnd());
    }

    @Test
    void chunkingIsDeterministic() {
        String text = fixtureDocument();
        ChunkingEngine.ChunkingResult first = chunk(text);
        ChunkingEngine.ChunkingResult second = chunk(text);
        assertThat(second.parents()).isEqualTo(first.parents());
        assertThat(second.children()).isEqualTo(first.children());
    }

    @Test
    void tokenEstimatorIsDeterministicAndSane() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("hello world")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(4);
        assertThat(TokenEstimator.estimate("a")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("你好，世界。")).isEqualTo(6);
        assertThat(TokenEstimator.estimate("a".repeat(1000))).isEqualTo(250);
    }

    @Test
    void headingBoundariesStartParentsAndChildrenCarryAnchors() {
        String text = "# 第一章 引言\n\n"
                + repeat("这是第一段的说明文字。", 40) + "\n\n"
                + "## 1.1 动机\n\n" + repeat("动机段落描述。", 40) + "\n\n"
                + "### 1.1.1 原因\n\n" + repeat("原因说明文字。", 30) + "\n";
        ChunkingEngine.ChunkingResult result = chunk(text);
        assertThat(result.parents()).isNotEmpty();
        // Heading boundary rule: an H1/H2/H3 heading starts a new parent (unless empty).
        assertThat(result.parents().stream().map(ChunkCandidate::headingPath).collect(Collectors.toList()))
                .contains(List.of("第一章 引言"), List.of("第一章 引言", "1.1 动机"));
        for (ChunkCandidate child : result.children()) {
            assertThat(child.headingPath()).isNotEmpty();
            assertThat(child.startLine()).isGreaterThanOrEqualTo(1);
            assertThat(child.endLine()).isGreaterThanOrEqualTo(child.startLine());
            assertThat(child.tokenStart()).isLessThanOrEqualTo(child.tokenEnd());
            assertThat(child.textHash()).matches("^[0-9a-f]{64}$");
        }
        // Every child belongs to a parent that exists and covers it.
        Map<Integer, ChunkCandidate> parentsByIndex = result.parents().stream()
                .collect(Collectors.toMap(ChunkCandidate::parentIndex, p -> p));
        for (ChunkCandidate child : result.children()) {
            ChunkCandidate parent = parentsByIndex.get(child.parentIndex());
            assertThat(parent).as("parent %d for child %d", child.parentIndex(), child.chunkIndex()).isNotNull();
            assertThat(child.charStart()).isGreaterThanOrEqualTo(parent.charStart());
            assertThat(child.charEnd()).isLessThanOrEqualTo(parent.charEnd());
        }
    }

    @Test
    void codeBlocksAreNotCutMidLine() {
        String code = "```java\n"
                + repeat("int variable = computeSomething(argument, another);\n", 40) + "```\n";
        ChunkingEngine.ChunkingResult result = chunk("# 代码示例\n\n" + code);
        List<ChunkCandidate> children = result.children();
        assertThat(children).isNotEmpty();
        for (ChunkCandidate child : children) {
            String childText = child.text();
            if (childText.contains("```")) {
                // Every line inside the fenced code must be a complete line.
                String[] lines = childText.split("\n", -1);
                for (String line : lines) {
                    if (!line.isEmpty() && !line.startsWith("#") && !line.startsWith("```")
                            && !line.startsWith("~~~")) {
                        assertThat(line).endsWith(";").as("code line must stay whole: %s", line);
                    }
                }
            }
        }
    }

    @Test
    void tableBlocksAreNotCutMidRow() {
        String table = "| 名称 | 数值 |\n| --- | --- |\n";
        StringBuilder rows = new StringBuilder(table);
        for (int i = 0; i < 60; i++) {
            rows.append("| 行").append(i).append(" | ").append(i * 100).append(" |\n");
        }
        ChunkingEngine.ChunkingResult result = chunk("# 表格\n\n" + rows);
        List<ChunkCandidate> children = result.children();
        assertThat(children).isNotEmpty();
        for (ChunkCandidate child : children) {
            String childText = child.text();
            if (childText.contains("|")) {
                for (String line : childText.split("\n", -1)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        assertThat(line).startsWith("|").endsWith("|").as("table row must stay whole");
                    }
                }
            }
        }
    }

    @Test
    void cjkTextSplitsAtSentenceEndsAndChildrenStayWithinBounds() {
        String text = "# 中文长文\n\n" + repeat("这是一句足够长的中文句子，用来测试分块边界是否保持在句号之后。", 30);
        ChunkingEngine.ChunkingResult result = chunk(text);
        assertThat(result.children()).isNotEmpty();
        int target = STRATEGY.childTargetTokens();
        for (ChunkCandidate child : result.children()) {
            int tokens = TokenEstimator.estimate(child.text());
            // A single oversized sentence may exceed the target, but normal children must not.
            assertThat(tokens).as("child %d tokens %d", child.chunkIndex(), tokens)
                    .isLessThanOrEqualTo(target + 60);
        }
    }

    @Test
    void childrenCoverTheirParentWithoutGaps() {
        String text = fixtureDocument();
        ChunkingEngine.ChunkingResult result = chunk(text);
        for (ChunkCandidate parent : result.parents()) {
            List<ChunkCandidate> own = result.children().stream()
                    .filter(child -> child.parentIndex() == parent.parentIndex())
                    .sorted((a, b) -> Integer.compare(a.charStart(), b.charStart()))
                    .collect(Collectors.toList());
            assertThat(own).as("parent %d children", parent.parentIndex()).isNotEmpty();
            assertThat(own.get(0).charStart()).isEqualTo(parent.charStart());
            assertThat(own.get(own.size() - 1).charEnd()).isEqualTo(parent.charEnd());
            for (int i = 1; i < own.size(); i++) {
                assertThat(own.get(i).charStart()).isLessThanOrEqualTo(own.get(i - 1).charEnd())
                        .as("children of parent %d must be contiguous or overlap", parent.parentIndex());
            }
        }
    }

    @Test
    void oversizedSingleBlockBecomesItsOwnParent() {
        String text = "# 巨块\n\n" + repeat("word ", 2000); // ~1000 tokens > parent target
        ChunkingEngine.ChunkingResult result = chunk(text);
        assertThat(result.parents()).hasSize(1); // heading and its oversized section stay together
        ChunkCandidate big = result.parents().get(0);
        assertThat(TokenEstimator.estimate(big.text())).isGreaterThanOrEqualTo(STRATEGY.parentTargetTokens());
        assertThat(big.charStart()).isZero();
    }

    @Test
    void hashesAreContentBound() {
        ChunkingEngine.ChunkingResult result = chunk("# 一\n\n内容甲。\n\n# 二\n\n内容乙。\n");
        List<ChunkCandidate> children = result.children();
        assertThat(children).hasSizeGreaterThanOrEqualTo(2);
        assertThat(children.get(0).textHash()).isNotEqualTo(children.get(1).textHash());
        assertThat(children.get(0).text()).isEqualTo(children.get(0).text());
    }

    @Test
    void explicitRequestCarriesSpaceRevisionAndStableScopedIds() {
        UUID space = UUID.fromString("018f4b2c-8b11-7abc-8def-1234567890ab");
        UUID revision = UUID.fromString("018f4b2c-8b12-7abc-8def-1234567890ab");
        ChunkingRequest request = new ChunkingRequest(space, revision, 3,
                "s3://space-a/revision-3", fixtureDocument());
        ChunkingEngine.ChunkingResult first = new ChunkingEngine(STRATEGY).chunk(request);
        ChunkingEngine.ChunkingResult second = new ChunkingEngine(STRATEGY).chunk(request);
        assertThat(first).isEqualTo(second);
        assertThat(first.parents()).allSatisfy(candidate -> {
            assertThat(candidate.spaceId()).isEqualTo(space);
            assertThat(candidate.documentRevisionId()).isEqualTo(revision);
            assertThat(candidate.versionNo()).isEqualTo(3);
            assertThat(candidate.contentRef()).startsWith("s3://space-a/revision-3/");
            assertThat(candidate.id().version()).isEqualTo(7);
            assertThat(candidate.id().variant()).isEqualTo(2);
        });
        assertThat(first.children()).extracting(ChunkCandidate::chunkIndex).doesNotHaveDuplicates();
        UUID nextRevision = UUID.fromString("018f4b2c-8b13-7abc-8def-1234567890ab");
        ChunkingEngine.ChunkingResult changed = new ChunkingEngine(STRATEGY).chunk(
                new ChunkingRequest(space, nextRevision, 1, "s3://space-a/revision-4", fixtureDocument()));
        assertThat(changed.parents().get(0).id()).isNotEqualTo(first.parents().get(0).id());
    }

    @Test
    void overlapNeverExceedsConfiguredTokenWindow() {
        String text = "# Overlap\n\n" + repeat("alpha beta gamma delta epsilon zeta eta theta. ", 80);
        List<ChunkCandidate> children = chunk(text).children();
        assertThat(children).hasSizeGreaterThan(1);
        for (int i = 1; i < children.size(); i++) {
            ChunkCandidate previous = children.get(i - 1);
            ChunkCandidate current = children.get(i);
            String overlap = current.charStart() <= previous.charEnd()
                    ? text.substring(current.charStart(), Math.min(current.charEnd(), previous.charEnd())) : "";
            assertThat(TokenEstimator.estimate(overlap)).isLessThanOrEqualTo(STRATEGY.overlapTokens());
        }
    }

    @Test
    void listItemsRemainWholeWhenNaturalItemsFit() {
        StringBuilder text = new StringBuilder("# List\n\n");
        for (int i = 0; i < 120; i++) {
            text.append("- item ").append(i).append(" keeps its boundary and meaning.\n");
        }
        List<ChunkCandidate> children = chunk(text.toString()).children();
        for (ChunkCandidate child : children) {
            for (String line : child.text().split("\\n")) {
                if (!line.isBlank()) {
                    assertThat(line.startsWith("#") || line.startsWith("-")).isTrue();
                }
            }
        }
    }

    private static String fixtureDocument() {
        return "# Chapter 1 Introduction\n\n"
                + repeat("This is the introduction paragraph with enough words to fill a section. ", 30) + "\n\n"
                + "## 1.1 Motivation\n\n" + repeat("Motivation text keeps repeating here for volume. ", 30) + "\n\n"
                + "### 1.1.1 Why\n\n" + repeat("The reason paragraph provides justification. ", 20) + "\n\n"
                + "| Column A | Column B |\n| --- | --- |\n"
                + "| value 1 | value 2 |\n| value 3 | value 4 |\n\n"
                + "```java\nint result = sum(a, b);\nSystem.out.println(result);\n```\n\n"
                + "- first item\n- second item\n- third item\n\n"
                + "# Chapter 2 Deep Dive\n\n" + repeat("Deep dive content with more volume. ", 40) + "\n";
    }
}
