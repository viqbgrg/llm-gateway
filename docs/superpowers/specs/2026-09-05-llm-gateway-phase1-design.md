# llm-gateway Phase 1 Design

## Goal

建立一个可运行的 llm-gateway 项目骨架，完成 Provider、Provider Model、Virtual Model 和 Virtual Model Binding 的持久化与管理 API，并为后续 Internal IR、协议适配、路由、健康、熔断和模型发现保留清晰边界。

## Architecture

后端使用单一 Gradle 模块和 Spring Boot WebFlux。管理 API 通过响应式 service/repository 访问 MySQL；运行态数据使用 Redis，Flyway 负责数据库迁移。领域对象严格区分 Provider、ProviderModel、VirtualModel 与 VirtualModelBinding。Internal IR、ClientProtocolAdapter、ProviderAdapter、ModelRouter、HealthManager、CircuitBreaker 和 ProviderModelDiscovery 在 Phase 1 以类型安全接口和基础实现契约落地，不在本阶段实现推理转发。

## Persistence

MySQL 持久化配置与拓扑：providers、provider_models、virtual_models、virtual_model_bindings、model_rules、routing_policies。Provider API key 仅在数据库字段和请求上下文中使用，管理 API 返回时脱敏。Redis 统一使用 `llm-gateway:` 命名空间保存 health、circuit、preferred、score 和 provider-model cache 等运行态键。

## Phase 1 Scope

- Gradle Kotlin DSL、Java 21 toolchain、Spring Boot 3、WebFlux、Reactive Redis、Flyway、MySQL 驱动。
- Vue 3 + TypeScript + Vite + Element Plus + Pinia 管理台基础页面。
- Provider、Provider Model、Virtual Model、Binding 的 CRUD API。
- 基础领域接口：Internal IR、协议适配器、Provider Adapter、Router、Health、Circuit Breaker、Discovery。
- Docker Compose 提供 gateway、mysql、redis。
- README 与 docs/architecture.md 说明边界和下一阶段演进方向。

## Out of Scope

Phase 1 不实现客户端推理协议、Provider HTTP 调用、流式传输、协议转换、自动发现调度、健康评分、重试、Fallback、Hedged Request 或后台完整仪表盘。

## Verification

`./gradlew build` 和 `./gradlew test` 必须通过；Docker Compose 文件必须能构建 gateway 镜像并连接 MySQL、Redis。
