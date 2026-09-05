package com.ragforge.server.run;

import com.ragforge.server.provider.adapter.ProviderAdapter;
import com.ragforge.server.provider.adapter.ProviderAdapterException;
import com.ragforge.server.provider.adapter.ProviderErrorClass;
import com.ragforge.server.provider.adapter.ProviderType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Explicit provider-type dispatch; adapter beans are never selected by ambiguous autowiring. */
@Component
public class ProviderAdapterRegistry {
    private final Map<ProviderType, ProviderAdapter> adapters;

    public ProviderAdapterRegistry(List<ProviderAdapter> adapterList) {
        EnumMap<ProviderType, ProviderAdapter> values = new EnumMap<>(ProviderType.class);
        for (ProviderAdapter adapter : adapterList) {
            ProviderAdapter previous = values.put(adapter.providerType(), adapter);
            if (previous != null) {
                throw new IllegalStateException("Multiple provider adapters are registered for " + adapter.providerType());
            }
        }
        adapters = Map.copyOf(values);
    }

    public ProviderAdapter require(ProviderType type) {
        ProviderAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new ProviderAdapterException(ProviderErrorClass.UNAVAILABLE,
                    "No provider adapter is configured for the requested provider type");
        }
        return adapter;
    }
}
