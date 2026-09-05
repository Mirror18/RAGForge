package com.ragforge.server.answer.integration;

import com.ragforge.server.answer.RetrievalPort;
import com.ragforge.server.answer.EvidenceBundleSnapshot;
import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.retrieval.EvidenceBundle;
import com.ragforge.server.retrieval.RetrievalProfileRepository;
import com.ragforge.server.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalServicePortAdapterTest {
    private static final UUID SPACE = UUID.fromString("018f0f70-8e10-7b14-8f1a-111111111111");
    private static final UUID INDEX = UUID.fromString("018f0f70-8e10-7b14-8f1a-222222222222");
    private static final UUID PROFILE_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-555555555555");
    private static final UUID PROFILE_VERSION_ID = UUID.fromString("018f0f70-8e10-7b14-8f1a-666666666666");
    private static final UUID REVISION = UUID.fromString("018f0f70-8e10-7b14-8f1a-333333333333");
    private static final UUID PARENT = UUID.fromString("018f0f70-8e10-7b14-8f1a-444444444444");
    private static final UUID CHILD = UUID.fromString("018f0f70-8e10-7b14-8f1a-888888888888");
    private static final UUID EVIDENCE = UUID.fromString("018f0f70-8e10-7b14-8f1a-999999999999");
    private static final UUID RUN = UUID.fromString("018f0f70-8e10-7b14-8f1a-aaaaaaaaaaaa");
    private static final UUID CORRELATION = UUID.fromString("018f0f70-8e10-7b14-8f1a-bbbbbbbbbbbb");
    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void materialResolverRejectsDenseOnlyFalsePositiveForSpecificExternalTerm() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenReturn(bundle("Linux 查看磁盘空间"));
        RetrievalServicePortAdapter adapter = adapter(retrieval, "前端项目的页面布局说明");

        EvidenceBundleSnapshot snapshot = adapter.retrieve(request("Linux 查看磁盘空间"), new CancellationToken());

        assertThat(snapshot.bundle().abstained()).isTrue();
        assertThat(snapshot.bundle().abstentionReason()).isEqualTo("NO_VERIFIED_EVIDENCE");
        assertThat(snapshot.bundle().evidence()).isEmpty();
    }

    @Test
    void materialResolverKeepsRelevantLinuxEvidenceForAnswerGeneration() {
        RetrievalService retrieval = mock(RetrievalService.class);
        when(retrieval.retrieve(any())).thenReturn(bundle("Linux 查看磁盘空间"));
        RetrievalServicePortAdapter adapter = adapter(retrieval, "Linux 上使用 df -h 查看磁盘空间。");

        EvidenceBundleSnapshot snapshot = adapter.retrieve(request("Linux 查看磁盘空间"), new CancellationToken());

        assertThat(snapshot.bundle().abstained()).isFalse();
        assertThat(snapshot.bundle().evidence()).hasSize(1);
        assertThat(snapshot.materialById()).containsKey(EVIDENCE);
    }

    private static RetrievalServicePortAdapter adapter(RetrievalService retrieval, String material) {
        RetrievalServicePortAdapter.EvidenceMaterialResolver materials = (evidence, request, token) -> material;
        RetrievalExecutionResolver execution = (space, run, correlation) -> new RetrievalExecutionResolver.Execution(
                SPACE, INDEX, profile(), UUID.fromString("018f0f70-8e10-7b14-8f1a-cccccccccccc"), 1,
                "evidence:linux", HASH, HASH);
        return new RetrievalServicePortAdapter(retrieval, execution, materials);
    }

    private static RetrievalPort.RetrievalRequest request(String query) {
        return new RetrievalPort.RetrievalRequest(SPACE, RUN, CORRELATION, query, List.of(0.1));
    }

    private static EvidenceBundle bundle(String query) {
        EvidenceBundle.Evidence evidence = new EvidenceBundle.Evidence(EVIDENCE, SPACE, INDEX, REVISION, PARENT,
                CHILD, "content:linux", HASH,
                new EvidenceBundle.Anchor(List.of("Linux"), 0, 4, 0, 10, null, null, null, 1, 2, null),
                .9, 0, .5, .4, "direct-hit");
        return new EvidenceBundle(SPACE, INDEX, PROFILE_ID, 1, query, query, List.of(evidence), false, null);
    }

    private static RetrievalProfileRepository.RetrievalProfileVersion profile() {
        return new RetrievalProfileRepository.RetrievalProfileVersion(PROFILE_VERSION_ID, SPACE, PROFILE_ID, 1,
                5, 5, 60, .5, .5, 5, 3, com.ragforge.server.retrieval.ExpansionMode.NONE, 0, 0, 100,
                Instant.parse("2026-08-21T00:00:00Z"));
    }
}
