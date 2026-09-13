package com.llmgateway.routing;

import com.llmgateway.model.*;
import com.llmgateway.protocol.ProtocolJson;
import java.util.EnumSet;
import java.util.Set;

public final class CapabilityChecker {
    private CapabilityChecker() {}
    public static ModelCapabilities parse(String json) {
        if (json == null) return null;
        try {
            var array = ProtocolJson.read(json);
            if (!array.isArray()) throw new IllegalArgumentException();
            var values = EnumSet.noneOf(ModelCapability.class);
            array.forEach(item -> values.add(ModelCapability.valueOf(ProtocolJson.text(item))));
            return new ModelCapabilities(values);
        } catch (Exception ignored) { throw new IllegalArgumentException("Capabilities must be an array of known capability names"); }
    }
    public static Set<ModelCapability> effective(ModelCapabilities model, ModelCapabilities override, Set<ModelCapability> chain) {
        var result = EnumSet.noneOf(ModelCapability.class);
        ModelCapabilities declared = override == null ? model : override;
        if (declared != null) result.addAll(declared.values());
        result.retainAll(chain);
        return Set.copyOf(result);
    }
    public static Set<ModelCapability> missing(Set<ModelCapability> required, Set<ModelCapability> available) {
        var missing = EnumSet.noneOf(ModelCapability.class); missing.addAll(required); missing.removeAll(available);
        return Set.copyOf(missing);
    }
}
