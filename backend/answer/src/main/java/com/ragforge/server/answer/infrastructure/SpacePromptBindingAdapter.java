package com.ragforge.server.answer.infrastructure;

import com.ragforge.server.space.application.SpacePromptBindingPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Adapts PromptRepository so space can bind prompts without importing answer infrastructure types. */
@Component
public class SpacePromptBindingAdapter implements SpacePromptBindingPort {
    private final PromptRepository prompts;

    public SpacePromptBindingAdapter(PromptRepository prompts) {
        this.prompts = prompts;
    }

    @Override
    public UUID bindActive(UUID id, UUID spaceId, String bindingKey, int versionNo, UUID promptVersionId,
                           Instant now, UUID correlationId) {
        return prompts.bind(new PromptRepository.NewSpacePromptBinding(
                id, spaceId, bindingKey, versionNo, promptVersionId,
                PromptRepository.BindingStatus.ACTIVE, now, correlationId)).id();
    }
}
