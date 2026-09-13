package com.llmgateway.admin;

import com.llmgateway.infrastructure.credentials.CredentialService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/admin/credentials")
public class CredentialController {
    private final CredentialService service;
    public CredentialController(CredentialService service) { this.service = service; }
    @PostMapping("/migrate") public Mono<CredentialService.MigrationResult> migrate(@RequestBody MigrationRequest request) {
        return service.migrate(request.batchSize(), request.rotate());
    }
    public record MigrationRequest(int batchSize, boolean rotate) {}
}
