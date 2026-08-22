package com.ragforge.server.answer;

import com.ragforge.server.provider.adapter.CancellationToken;
import com.ragforge.server.provider.adapter.EgressDecision;
import com.ragforge.server.provider.adapter.ProviderUsage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface GenerationPort {
    CompletionStage<GenerationResult> generate(GenerationRequest request, CancellationToken cancellationToken);

    record GenerationRequest(UUID spaceId, UUID runId, UUID correlationId, String idempotencyKey,
                             String query, RagPromptPort.VersionedRagPrompt prompt, String renderedPrompt,
                             EvidenceBundleSnapshot evidenceBundle, String model,
                             UUID modelRouteVersionId, UUID modelProfileVersionId,
                             EgressDecision egressDecision) {
        public GenerationRequest {
            if (spaceId == null || runId == null || correlationId == null || idempotencyKey == null
                    || query == null || query.isBlank() || prompt == null || renderedPrompt == null
                    || renderedPrompt.isBlank() || evidenceBundle == null || model == null || model.isBlank()
                    || modelRouteVersionId == null || modelProfileVersionId == null || egressDecision == null) {
                throw new IllegalArgumentException("Generation request is invalid");
            }
            if (!spaceId.equals(prompt.spaceId()) || !spaceId.equals(evidenceBundle.bundle().spaceId())) {
                throw new IllegalArgumentException("Generation request crosses space");
            }
        }
    }

    record GenerationResult(String answerText, List<GeneratedClaim> claims, String model,
                            EgressDecision egressDecision, ProviderUsage usage) {
        public GenerationResult(String answerText, List<GeneratedClaim> claims, String model,
                                 EgressDecision egressDecision) {
            this(answerText, claims, model, egressDecision, null);
        }

        public GenerationResult {
            if (answerText == null || answerText.isBlank() || claims == null || claims.isEmpty()
                    || model == null || model.isBlank() || egressDecision == null) {
                throw new IllegalArgumentException("Generation result is incomplete");
            }
            claims = List.copyOf(claims);
        }
    }

    record GeneratedClaim(String claimText, List<String> citationTokens, Integer answerCharStart,
                          Integer answerCharEnd) {
        public GeneratedClaim {
            if (claimText == null || claimText.isBlank() || citationTokens == null || citationTokens.isEmpty()) {
                throw new IllegalArgumentException("Generated claim is incomplete");
            }
            citationTokens = List.copyOf(citationTokens);
        }

        public GeneratedClaim(String claimText, List<String> citationTokens) {
            this(claimText, citationTokens, null, null);
        }
    }
}
