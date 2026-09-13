package com.llmgateway.admin;

import com.llmgateway.model.*;
import com.llmgateway.routing.DefaultModelRouter;
import com.llmgateway.routing.ConfigurationValidationService;
import java.util.List;
import java.util.Set;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
public class RoutingPreviewController {
    private final DefaultModelRouter router;
    private final ConfigurationValidationService validation;
    public RoutingPreviewController(DefaultModelRouter router, ConfigurationValidationService validation) { this.router = router; this.validation = validation; }
    @GetMapping("/api/admin/routing/validation") public Mono<List<ConfigurationValidationService.Issue>> validation() { return validation.validate(); }
    @PostMapping("/api/admin/routing/preview") public Mono<PreviewResponse> preview(@Valid @RequestBody PreviewRequest request) {
        return router.preview(request.model(), request.protocol(), request.capabilities() == null ? Set.of(ModelCapability.CHAT) : request.capabilities())
                .map(d -> new PreviewResponse(d.configuration().virtualModel().id(), d.configuration().virtualModel().name(), d.routingReason(),
                        d.candidateBindings().stream().map(c -> c.binding().id()).toList(), d.evaluation()));
    }
    public record PreviewRequest(@NotBlank @Size(max = 255) String model, @NotNull Protocol protocol, Set<ModelCapability> capabilities) {}
    public record PreviewResponse(String virtualModelId, String resolvedModel, String strategy, List<String> candidateOrder,
                                  List<DefaultModelRouter.CandidateView> candidates) {}
}
