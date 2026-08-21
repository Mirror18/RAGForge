package com.ragforge.server.studio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ragforge.server.audit.AuditOutboxService;
import com.ragforge.server.common.ApiException;
import com.ragforge.server.common.UuidV7;
import com.ragforge.server.identity.SessionPrincipal;
import com.ragforge.server.index.CandidateIndexService;
import com.ragforge.server.index.CandidateIndexStore;
import com.ragforge.server.index.IndexRepository;
import com.ragforge.server.provider.SpaceAuthorization;
import com.ragforge.server.retrieval.Bm25CandidateStore;
import com.ragforge.server.retrieval.ChunkCatalog;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.retrieval.Reranker;
import com.ragforge.server.retrieval.RetrievalCandidate;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.retrieval.RetrievalService;
import com.ragforge.server.retrieval.RrfMerger;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only A/B retrieval experiment adapter with a structured, redacted trace. */
@Service
public class RetrievalPlaygroundService {
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProfileRef(@NotNull UUID profileId, @NotNull @Min(1) Integer version,
            @AssertTrue boolean candidateOnly) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ExperimentRequest(@NotBlank @Size(max = 10000) String query,
            @NotNull UUID indexVersionId, @NotNull @Valid ProfileRef profileA,
            @Valid ProfileRef profileB, @Size(max = 4096) List<Double> queryVector) {
    }

    public record TraceHit(UUID childChunkId, UUID documentRevisionId, int rank, double score,
            String contentRef, String textHash) {
    }

    public record StageMetrics(int candidateCount, double latencyMs) {
    }

    public record StageTrace(List<TraceHit> items, StageMetrics metrics) {
    }

    public record ContextTrace(List<UUID> childChunkIds, int totalTokens, int maxContextTokens,
            boolean truncated) {
    }

    public record CitationEvidence(UUID evidenceId, UUID spaceId, UUID childChunkId,
            UUID documentRevisionId, String contentRef, String textHash,
            ChunkStudioService.Anchor anchor, boolean citationAllowed) {
    }

    public record EvidenceTrace(List<CitationEvidence> items, String allowListVersion) {
    }

    public record RetrievalTrace(StageTrace dense, StageTrace bm25, StageTrace rrf,
            StageTrace rerank, ContextTrace context, EvidenceTrace evidence) {
    }

    public record ProfileSide(UUID indexVersionId, ProfileRef profile, RetrievalTrace trace,
            SideMetrics metrics) {
    }

    public record SideMetrics(double latencyMs, int evidenceCount) {
    }

    public record Abstention(boolean abstained, String reasonCode) {
    }

    public record AbstentionPair(Abstention profileA, Abstention profileB) {
    }

    public record Experiment(UUID experimentId, UUID spaceId, String query, String normalizedQuery,
            UUID indexVersionId, ProfileSide profileA, ProfileSide profileB,
            AbstentionPair abstention, boolean activeProfileUnchanged) {
    }

    private final SpaceAuthorization authorization;
    private final RetrievalProfileRepository profiles;
    private final IndexRepository indexes;
    private final CandidateIndexStore denseStore;
    private final Bm25CandidateStore bm25Store;
    private final ChunkCatalog catalog;
    private final Reranker reranker;
    private final RetrievalService retrieval;
    private final AuditOutboxService audit;

    public RetrievalPlaygroundService(SpaceAuthorization authorization, RetrievalProfileRepository profiles,
                                      IndexRepository indexes, CandidateIndexStore denseStore,
                                      Bm25CandidateStore bm25Store, ChunkCatalog catalog,
                                      Reranker reranker, RetrievalService retrieval,
                                      AuditOutboxService audit) {
        this.authorization = authorization;
        this.profiles = profiles;
        this.indexes = indexes;
        this.denseStore = denseStore;
        this.bm25Store = bm25Store;
        this.catalog = catalog;
        this.reranker = reranker;
        this.retrieval = retrieval;
        this.audit = audit;
    }

    @Transactional
    public Experiment run(UUID spaceId, ExperimentRequest request, SessionPrincipal principal, UUID correlationId) {
        authorization.requireMember(spaceId, principal);
        if (request == null || request.query() == null || request.query().isBlank()
                || request.query().length() > 10000 || request.indexVersionId() == null || request.profileA() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "experiment_request_invalid", "Invalid experiment request",
                    "The experiment request is incomplete");
        }
        validateQueryVector(request.queryVector());
        IndexRepository.IndexVersion index = indexes.findVersion(spaceId, request.indexVersionId())
                .orElseThrow(() -> notFound("index_version_not_found", "Index version not found"));
        RetrievalProfileRepository.RetrievalProfileVersion profileA = findProfile(spaceId, request.profileA());
        RetrievalProfileRepository.RetrievalProfileVersion profileB = request.profileB() == null
                ? null : findProfile(spaceId, request.profileB());
        String normalizedQuery;
        try {
            normalizedQuery = RetrievalService.normalizeQuery(request.query());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "query_invalid", "Invalid query", "The query is invalid");
        }

        SideResult sideA = runSide(spaceId, index.id(), profileA, request.query(), normalizedQuery,
                request.queryVector());
        SideResult sideB = profileB == null ? null
                : runSide(spaceId, index.id(), profileB, request.query(), normalizedQuery, request.queryVector());
        UUID experimentId = UuidV7.random();
        audit.record("retrieval.playground.experiment", principal.userId(), spaceId, experimentId, correlationId,
                Map.ofEntries(Map.entry("experimentId", experimentId), Map.entry("indexVersionId", index.id()),
                        Map.entry("profileAId", profileA.profileId()), Map.entry("profileAVersion", profileA.versionNo()),
                        Map.entry("profileBId", profileB == null ? "NONE" : profileB.profileId()),
                        Map.entry("profileBVersion", profileB == null ? 0 : profileB.versionNo()),
                        Map.entry("profileAEvidenceCount", sideA.bundle().evidence().size()),
                        Map.entry("profileBEvidenceCount", sideB == null ? 0 : sideB.bundle().evidence().size()),
                        Map.entry("profileAAbstained", sideA.bundle().abstained()),
                        Map.entry("profileBAbstained", sideB != null && sideB.bundle().abstained()),
                        Map.entry("reasonCode", "PLAYGROUND_READ_ONLY")));
        return new Experiment(experimentId, spaceId, request.query(), normalizedQuery, index.id(),
                sideA.response(), sideB == null ? null : sideB.response(),
                new AbstentionPair(abstention(sideA.bundle()), sideB == null ? null : abstention(sideB.bundle())),
                true);
    }

    private SideResult runSide(UUID spaceId, UUID indexVersionId,
                               RetrievalProfileRepository.RetrievalProfileVersion profile,
                               String originalQuery, String normalizedQuery, List<Double> queryVector) {
        long started = System.nanoTime();
        String collection = CandidateIndexService.collectionFor(spaceId, indexVersionId);
        long denseStarted = System.nanoTime();
        List<RetrievalCandidate> dense = denseStore.search(collection, spaceId, indexVersionId, queryVector,
                        profile.denseTopK()).stream()
                .map(hit -> new RetrievalCandidate(hit.spaceId(), hit.indexVersionId(), hit.id(),
                        hit.documentRevisionId(), hit.parentChunkId(), hit.contentRef(), hit.textHash(), hit.score(),
                        RetrievalCandidate.Source.DENSE, ""))
                .toList();
        StageMetrics denseMetrics = metrics(dense.size(), denseStarted);

        long bm25Started = System.nanoTime();
        List<RetrievalCandidate> bm25 = bm25Store.search(spaceId, indexVersionId, normalizedQuery, profile.bm25TopK());
        StageMetrics bm25Metrics = metrics(bm25.size(), bm25Started);

        long rrfStarted = System.nanoTime();
        List<RrfMerger.MergedCandidate> merged = RrfMerger.merge(spaceId, indexVersionId, dense, bm25,
                profile.rrfK(), profile.rrfDenseWeight(), profile.rrfBm25Weight());
        StageMetrics rrfMetrics = metrics(merged.size(), rrfStarted);

        long rerankStarted = System.nanoTime();
        List<Reranker.Result> reranked = reranker.rerank(normalizedQuery, merged, profile.rerankTopK());
        StageMetrics rerankMetrics = metrics(reranked.size(), rerankStarted);

        EvidenceBundle bundle;
        try {
            bundle = retrieval.retrieve(new RetrievalService.Request(spaceId, indexVersionId, profile,
                    originalQuery, queryVector));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "retrieval_trace_unavailable",
                    "Retrieval trace unavailable", "The configured retrieval pipeline rejected the experiment");
        }
        long elapsed = System.nanoTime() - started;
        RetrievalTrace trace = new RetrievalTrace(
                new StageTrace(toDenseHits(dense), denseMetrics),
                new StageTrace(toCandidateHits(bm25), bm25Metrics),
                new StageTrace(toMergedHits(merged), rrfMetrics),
                new StageTrace(toRerankHits(reranked), rerankMetrics),
                toContext(bundle, profile), toEvidence(bundle));
        ProfileSide response = new ProfileSide(indexVersionId,
                new ProfileRef(profile.profileId(), profile.versionNo(), true), trace,
                new SideMetrics(milliseconds(elapsed), bundle.evidence().size()));
        return new SideResult(response, bundle);
    }

    private RetrievalProfileRepository.RetrievalProfileVersion findProfile(UUID spaceId, ProfileRef ref) {
        if (ref == null || ref.profileId() == null || ref.version() == null || ref.version() < 1
                || !ref.candidateOnly()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "profile_ref_invalid", "Invalid profile reference",
                    "A profile id and positive version are required");
        }
        return profiles.findVersion(spaceId, ref.profileId(), ref.version())
                .orElseThrow(() -> notFound("retrieval_profile_not_found", "Retrieval profile version not found"));
    }

    private static void validateQueryVector(List<Double> queryVector) {
        if (queryVector == null || queryVector.isEmpty() || queryVector.size() > 4096
                || queryVector.stream().anyMatch(value -> value == null || !Double.isFinite(value))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "query_vector_required",
                    "Query vector required", "A finite internal queryVector is required for this P4-F seam");
        }
    }

    private static StageMetrics metrics(int count, long started) {
        return new StageMetrics(count, milliseconds(System.nanoTime() - started));
    }

    private static double milliseconds(long nanos) {
        return Duration.ofNanos(Math.max(0, nanos)).toNanos() / 1_000_000.0;
    }

    private static List<TraceHit> toDenseHits(List<RetrievalCandidate> values) {
        return toCandidateHits(values);
    }

    private static List<TraceHit> toCandidateHits(List<RetrievalCandidate> values) {
        return values.stream().map(value -> new TraceHit(value.childChunkId(), value.documentRevisionId(),
                values.indexOf(value) + 1, value.sourceScore(), value.contentRef(), value.textHash())).toList();
    }

    private static List<TraceHit> toMergedHits(List<RrfMerger.MergedCandidate> values) {
        return values.stream().map(value -> new TraceHit(value.childChunkId(), value.documentRevisionId(),
                values.indexOf(value) + 1, value.rrfScore(), value.contentRef(), value.textHash())).toList();
    }

    private static List<TraceHit> toRerankHits(List<Reranker.Result> values) {
        return values.stream().map(value -> new TraceHit(value.candidate().childChunkId(),
                value.candidate().documentRevisionId(), values.indexOf(value) + 1, value.score(),
                value.candidate().contentRef(), value.candidate().textHash())).toList();
    }

    private static ContextTrace toContext(EvidenceBundle bundle,
                                          RetrievalProfileRepository.RetrievalProfileVersion profile) {
        int totalTokens = bundle.evidence().stream().mapToInt(value ->
                Math.max(1, value.anchor().tokenEnd() - value.anchor().tokenStart())).sum();
        return new ContextTrace(bundle.evidence().stream().map(EvidenceBundle.Evidence::childChunkId).toList(),
                totalTokens, profile.maxContextTokens(), totalTokens >= profile.maxContextTokens()
                        && profile.maxContextTokens() > 0);
    }

    private static EvidenceTrace toEvidence(EvidenceBundle bundle) {
        List<CitationEvidence> items = bundle.evidence().stream().map(value -> new CitationEvidence(
                value.evidenceId(), value.spaceId(), value.childChunkId(), value.documentRevisionId(),
                value.contentRef(), value.textHash(), toAnchor(value.anchor()), true)).toList();
        return new EvidenceTrace(items, "evidence-allow-list-v1");
    }

    private static ChunkStudioService.Anchor toAnchor(EvidenceBundle.Anchor anchor) {
        return new ChunkStudioService.Anchor(anchor.headingPath(), anchor.pageNumber(), anchor.sheet(),
                anchor.slideNumber(), anchor.lineStart() == null ? null
                        : new ChunkStudioService.LineRange(anchor.lineStart(), anchor.lineEnd()), anchor.tableCell());
    }

    private static Abstention abstention(EvidenceBundle bundle) {
        return new Abstention(bundle.abstained(), bundle.abstained() ? "NO_EVIDENCE" : null);
    }

    private static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, "Not found", detail);
    }

    private record SideResult(ProfileSide response, EvidenceBundle bundle) {
    }
}
