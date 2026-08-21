package com.ragforge.server.chunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, boundary-aware parent/child chunking engine.
 *
 * <p>The engine consumes normalized text plus explicit space and revision
 * provenance. It preserves headings, tables, fenced code and list-item
 * boundaries, and only falls back to token-aligned splitting when one natural
 * unit is larger than the child target. Candidate IDs are stable for the same
 * space, revision, strategy and ordinal while remaining UUIDv7-shaped for the
 * public contract.</p>
 */
public final class ChunkingEngine {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^\\s*(`{3,}|~{3,}).*$");
    private static final Pattern TABLE_ROW = Pattern.compile("^\\s*\\|.*\\|\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^\\s*(?:[-*+]|\\d+[.)])\\s+");
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?]");
    private static final UUID LEGACY_SPACE_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID LEGACY_REVISION_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private enum BlockType { HEADING, TEXT, TABLE, CODE, LIST }

    private record Block(BlockType type, List<String> headingPath, int startChar, int endChar,
            int startLine, int endLine) {
    }

    private record Segment(int startChar, int endChar, List<String> headingPath, BlockType type) {
    }

    private record ParentGroup(int index, List<Block> blocks, int startChar, int endChar) {
    }

    public record ChunkingResult(List<ChunkCandidate> parents, List<ChunkCandidate> children) {
    }

    private final ChunkingStrategy strategy;

    public ChunkingEngine(ChunkingStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("strategy must not be null");
        }
        this.strategy = strategy;
    }

    /** Compatibility entry point for pure engine tests without persisted provenance. */
    public ChunkingResult chunk(String normalizedText) {
        return chunk(new ChunkingRequest(LEGACY_SPACE_ID, LEGACY_REVISION_ID, 1,
                "chunk://legacy/revision-1", normalizedText));
    }

    /** Chunks one explicitly scoped, versioned document revision. */
    public ChunkingResult chunk(ChunkingRequest request) {
        String text = normalize(request.normalizedText());
        if (text.isBlank()) {
            return new ChunkingResult(List.of(), List.of());
        }
        int[] lineStarts = lineStarts(text);
        List<Block> blocks = parseBlocks(text, lineStarts);
        List<ParentGroup> groups = buildParents(blocks, text);
        List<ChunkCandidate> parents = new ArrayList<>();
        List<ChunkCandidate> children = new ArrayList<>();
        int childOrdinal = 0;
        for (ParentGroup group : groups) {
            parents.add(parentCandidate(request, group, text));
            List<ChunkCandidate> groupChildren = buildChildren(request, group, text, childOrdinal);
            children.addAll(groupChildren);
            childOrdinal += groupChildren.size();
        }
        return new ChunkingResult(List.copyOf(parents), List.copyOf(children));
    }

    private static String normalize(String text) {
        return (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
    }

    // ------------------------------------------------------------------ blocks

    private List<Block> parseBlocks(String text, int[] lineStarts) {
        List<Block> blocks = new ArrayList<>();
        List<String> headingPath = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            if (line.isBlank()) {
                index++;
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                trimHeadingPath(headingPath, level);
                headingPath.add(heading.group(2).trim());
                blocks.add(block(BlockType.HEADING, headingPath, index, index, lineStarts, lines));
                index++;
                continue;
            }
            if (CODE_FENCE.matcher(line).matches()) {
                int startIndex = index++;
                boolean closed = false;
                while (index < lines.length) {
                    if (CODE_FENCE.matcher(lines[index]).matches()) {
                        closed = true;
                        index++;
                        break;
                    }
                    index++;
                }
                int endIndex = closed ? index - 1 : lines.length - 1;
                blocks.add(block(BlockType.CODE, headingPath, startIndex, endIndex, lineStarts, lines));
                continue;
            }
            if (TABLE_ROW.matcher(line).matches()) {
                int startIndex = index++;
                while (index < lines.length && TABLE_ROW.matcher(lines[index]).matches()) {
                    index++;
                }
                blocks.add(block(BlockType.TABLE, headingPath, startIndex, index - 1, lineStarts, lines));
                continue;
            }
            if (LIST_ITEM.matcher(line).find()) {
                int startIndex = index++;
                while (index < lines.length && !lines[index].isBlank()
                        && (LIST_ITEM.matcher(lines[index]).find() || isContinuation(lines[index]))) {
                    index++;
                }
                blocks.add(block(BlockType.LIST, headingPath, startIndex, index - 1, lineStarts, lines));
                continue;
            }
            int startIndex = index++;
            while (index < lines.length && !lines[index].isBlank()
                    && !HEADING.matcher(lines[index]).matches()
                    && !CODE_FENCE.matcher(lines[index]).matches()
                    && !TABLE_ROW.matcher(lines[index]).matches()
                    && !LIST_ITEM.matcher(lines[index]).find()) {
                index++;
            }
            blocks.add(block(BlockType.TEXT, headingPath, startIndex, index - 1, lineStarts, lines));
        }
        return List.copyOf(blocks);
    }

    private static Block block(BlockType type, List<String> headingPath, int startLine, int endLine,
            int[] lineStarts, String[] lines) {
        int start = lineStarts[startLine];
        int end = lineStarts[endLine] + lines[endLine].length();
        return new Block(type, List.copyOf(headingPath), start, end, startLine, endLine);
    }

    private static boolean isContinuation(String line) {
        return line.matches("^\\s{2,}.*") || line.matches("^\\s*`{3,}.*");
    }

    private static void trimHeadingPath(List<String> path, int level) {
        while (path.size() >= level) {
            path.remove(path.size() - 1);
        }
    }

    private static int[] lineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                starts.add(index + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    // ---------------------------------------------------------------- parents

    private List<ParentGroup> buildParents(List<Block> blocks, String text) {
        List<ParentGroup> groups = new ArrayList<>();
        List<Block> current = new ArrayList<>();
        int currentTokens = 0;
        for (Block block : blocks) {
            int blockTokens = TokenEstimator.estimate(text.substring(block.startChar(), block.endChar()));
            if (block.type() == BlockType.HEADING) {
                flush(current, groups);
                current = new ArrayList<>(List.of(block));
                currentTokens = blockTokens;
                continue;
            }
            boolean onlyHeading = current.size() == 1 && current.get(0).type() == BlockType.HEADING;
            if (!current.isEmpty() && !onlyHeading && currentTokens + blockTokens > strategy.parentTargetTokens()) {
                flush(current, groups);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(block);
            currentTokens += blockTokens;
            if (blockTokens >= strategy.parentTargetTokens()) {
                flush(current, groups);
                current = new ArrayList<>();
                currentTokens = 0;
            }
        }
        flush(current, groups);
        return groups;
    }

    private static void flush(List<Block> current, List<ParentGroup> groups) {
        if (current.isEmpty()) {
            return;
        }
        groups.add(new ParentGroup(groups.size(), List.copyOf(current),
                current.get(0).startChar(), current.get(current.size() - 1).endChar()));
    }

    // ---------------------------------------------------------------- children

    private List<ChunkCandidate> buildChildren(ChunkingRequest request, ParentGroup group, String text,
            int childOrdinal) {
        List<Segment> pieces = new ArrayList<>();
        for (Block block : group.blocks()) {
            pieces.addAll(splitBlock(block, text));
        }
        List<ChunkCandidate> children = new ArrayList<>();
        List<Segment> current = new ArrayList<>();
        int currentTokens = 0;
        int previousEnd = -1;
        List<Segment> previousPieces = List.of();
        int chunkIndex = childOrdinal;
        for (Segment piece : pieces) {
            int pieceTokens = TokenEstimator.estimate(text.substring(piece.startChar(), piece.endChar()));
            if (!current.isEmpty() && currentTokens + pieceTokens > strategy.childTargetTokens()) {
                ChunkCandidate candidate = childCandidate(request, group, current, previousPieces, text,
                        chunkIndex++, previousEnd);
                children.add(candidate);
                previousEnd = candidate.charEnd();
                previousPieces = List.copyOf(current);
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(piece);
            currentTokens += pieceTokens;
        }
        if (!current.isEmpty()) {
            children.add(childCandidate(request, group, current, previousPieces, text, chunkIndex, previousEnd));
        }
        return List.copyOf(children);
    }

    private List<Segment> splitBlock(Block block, String text) {
        int blockTokens = TokenEstimator.estimate(text.substring(block.startChar(), block.endChar()));
        if (block.type() == BlockType.HEADING || blockTokens <= strategy.childTargetTokens()) {
            return List.of(new Segment(block.startChar(), block.endChar(), block.headingPath(), block.type()));
        }
        return switch (block.type()) {
            case CODE, TABLE -> splitLines(block, text, false);
            case LIST -> splitLines(block, text, true);
            case TEXT -> splitSentences(block, text);
            case HEADING -> List.of(new Segment(block.startChar(), block.endChar(), block.headingPath(), block.type()));
        };
    }

    private List<Segment> splitLines(Block block, String text, boolean listItems) {
        List<Segment> units = listItems ? listItemUnits(block, text) : lineUnits(block, text);
        return groupUnits(units, text, block.headingPath(), block.type(), true);
    }

    private List<Segment> lineUnits(Block block, String text) {
        List<Segment> units = new ArrayList<>();
        int start = block.startChar();
        for (int index = block.startChar(); index < block.endChar(); index++) {
            if (text.charAt(index) == '\n') {
                if (start < index) {
                    units.add(new Segment(start, index, List.of(), block.type()));
                }
                start = index + 1;
            }
        }
        if (start < block.endChar()) {
            units.add(new Segment(start, block.endChar(), List.of(), block.type()));
        }
        return units;
    }

    private List<Segment> listItemUnits(Block block, String text) {
        List<Segment> units = new ArrayList<>();
        int currentStart = -1;
        int lineStart = block.startChar();
        for (int index = block.startChar(); index <= block.endChar(); index++) {
            boolean end = index == block.endChar() || text.charAt(index) == '\n';
            if (!end) {
                continue;
            }
            String line = text.substring(lineStart, index);
            if (LIST_ITEM.matcher(line).find()) {
                if (currentStart >= 0) {
                    units.add(new Segment(currentStart, lineStart, List.of(), BlockType.LIST));
                }
                currentStart = lineStart;
            }
            lineStart = index + 1;
        }
        if (currentStart >= 0) {
            units.add(new Segment(currentStart, block.endChar(), List.of(), BlockType.LIST));
        }
        return units;
    }

    private List<Segment> groupUnits(List<Segment> units, String text, List<String> headingPath,
            BlockType type, boolean preserveOversizedUnit) {
        List<Segment> pieces = new ArrayList<>();
        int currentStart = -1;
        int currentEnd = -1;
        int currentUnitCount = 0;
        for (Segment unit : units) {
            if (currentStart < 0) {
                currentStart = unit.startChar();
                currentEnd = unit.endChar();
                currentUnitCount = 1;
                continue;
            }
            int candidateEnd = unit.endChar();
            if (TokenEstimator.estimate(text.substring(currentStart, candidateEnd)) > strategy.childTargetTokens()) {
                pieces.addAll(emitNaturalGroup(currentStart, currentEnd, currentUnitCount, text,
                        headingPath, type, preserveOversizedUnit));
                currentStart = unit.startChar();
                currentUnitCount = 1;
            } else {
                currentUnitCount++;
            }
            currentEnd = candidateEnd;
        }
        if (currentStart >= 0) {
            pieces.addAll(emitNaturalGroup(currentStart, currentEnd, currentUnitCount, text,
                    headingPath, type, preserveOversizedUnit));
        }
        return List.copyOf(pieces);
    }

    private List<Segment> emitNaturalGroup(int start, int end, int unitCount, String text,
            List<String> headingPath, BlockType type, boolean preserveOversizedUnit) {
        if (preserveOversizedUnit && unitCount == 1
                && TokenEstimator.estimate(text.substring(start, end)) > strategy.childTargetTokens()) {
            return List.of(new Segment(start, end, headingPath, type));
        }
        return oversizedSegment(start, end, text, headingPath, type);
    }

    private List<Segment> splitSentences(Block block, String text) {
        String content = text.substring(block.startChar(), block.endChar());
        Matcher matcher = SENTENCE_END.matcher(content);
        List<Segment> units = new ArrayList<>();
        int localStart = 0;
        while (matcher.find()) {
            int localEnd = matcher.end();
            if (localEnd > localStart) {
                units.add(new Segment(block.startChar() + localStart, block.startChar() + localEnd,
                        block.headingPath(), BlockType.TEXT));
            }
            localStart = localEnd;
        }
        if (localStart < content.length()) {
            units.add(new Segment(block.startChar() + localStart, block.endChar(), block.headingPath(), BlockType.TEXT));
        }
        if (units.isEmpty()) {
            return oversizedSegment(block.startChar(), block.endChar(), text, block.headingPath(), BlockType.TEXT);
        }
        return groupUnits(units, text, block.headingPath(), BlockType.TEXT, false);
    }

    private List<Segment> oversizedSegment(int start, int end, String text, List<String> headingPath, BlockType type) {
        if (TokenEstimator.estimate(text.substring(start, end)) <= strategy.childTargetTokens()) {
            return List.of(new Segment(start, end, headingPath, type));
        }
        List<Integer> ends = TokenEstimator.tokenEnds(text.substring(start, end));
        List<Segment> parts = new ArrayList<>();
        int partStart = start;
        int tokenCount = 0;
        for (int localEnd : ends) {
            tokenCount++;
            if (tokenCount == strategy.childTargetTokens()) {
                parts.add(new Segment(partStart, start + localEnd, headingPath, type));
                partStart = start + localEnd;
                tokenCount = 0;
            }
        }
        if (partStart < end) {
            parts.add(new Segment(partStart, end, headingPath, type));
        }
        return parts.isEmpty() ? List.of(new Segment(start, end, headingPath, type)) : List.copyOf(parts);
    }

    // ---------------------------------------------------------------- mapping

    private ChunkCandidate parentCandidate(ChunkingRequest request, ParentGroup group, String text) {
        String parentText = text.substring(group.startChar(), group.endChar());
        return new ChunkCandidate(request.spaceId(), request.documentRevisionId(), request.versionNo(),
                contentRef(request, "parent", group.index()), ChunkCandidate.Kind.PARENT,
                deterministicId(request, "parent", group.index(), 0), null, group.index(), group.index(),
                group.blocks().get(0).headingPath(), TokenEstimator.estimate(text.substring(0, group.startChar())),
                TokenEstimator.estimate(text.substring(0, group.endChar())), group.startChar(), group.endChar(),
                lineForOffset(text, group.startChar()) + 1, lineForOffset(text, group.endChar()) + 1,
                parentText, sha256(parentText));
    }

    private ChunkCandidate childCandidate(ChunkingRequest request, ParentGroup group, List<Segment> pieces,
            List<Segment> previousPieces, String text, int chunkIndex, int previousEnd) {
        Segment first = pieces.get(0);
        Segment last = pieces.get(pieces.size() - 1);
        int charStart = first.startChar();
        if (previousEnd >= 0 && strategy.overlapTokens() > 0) {
            if (!previousPieces.isEmpty() && first.type() == previousPieces.get(previousPieces.size() - 1).type()
                    && first.type() != BlockType.TEXT) {
                int sourceStart = previousEnd;
                for (int index = previousPieces.size() - 1; index >= 0; index--) {
                    sourceStart = previousPieces.get(index).startChar();
                    if (TokenEstimator.estimate(text.substring(sourceStart, previousEnd)) >= strategy.overlapTokens()) {
                        break;
                    }
                }
                charStart = Math.max(group.startChar(), sourceStart);
            } else {
                int overlapStart = TokenEstimator.tailStart(text.substring(group.startChar(), first.startChar()),
                        strategy.overlapTokens()) + group.startChar();
                charStart = Math.max(group.startChar(), overlapStart);
            }
        }
        int charEnd = last.endChar();
        String childText = text.substring(charStart, charEnd);
        return new ChunkCandidate(request.spaceId(), request.documentRevisionId(), request.versionNo(),
                contentRef(request, "child", chunkIndex), ChunkCandidate.Kind.CHILD,
                deterministicId(request, "child", group.index(), chunkIndex),
                deterministicId(request, "parent", group.index(), 0), group.index(), chunkIndex,
                first.headingPath().isEmpty() ? group.blocks().get(0).headingPath() : first.headingPath(),
                TokenEstimator.estimate(text.substring(0, charStart)),
                TokenEstimator.estimate(text.substring(0, charEnd)), charStart, charEnd,
                lineForOffset(text, charStart) + 1, lineForOffset(text, charEnd) + 1, childText, sha256(childText));
    }

    private static String contentRef(ChunkingRequest request, String kind, int index) {
        return request.contentRefPrefix().replaceAll("/+$", "") + "/" + kind + "/" + index;
    }

    private static int lineForOffset(String text, int offset) {
        int line = 0;
        for (int index = 0; index < Math.min(offset, text.length()); index++) {
            if (text.charAt(index) == '\n') {
                line++;
            }
        }
        return line;
    }

    private UUID deterministicId(ChunkingRequest request, String kind, int parentIndex, int chunkIndex) {
        String key = request.spaceId() + ":" + request.documentRevisionId() + ":" + request.versionNo()
                + ":" + request.contentRefPrefix() + ":" + strategy.strategyVersion() + ":" + kind + ":"
                + parentIndex + ":" + chunkIndex;
        UUID named = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        long most = (named.getMostSignificantBits() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000007000L;
        long least = (named.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
