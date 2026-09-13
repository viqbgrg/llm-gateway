plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

extra["testcontainers.version"] = "1.21.4"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework:spring-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    implementation("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("io.asyncer:r2dbc-mysql:1.2.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("llm-gateway.jar")
}

tasks.register<JavaExec>("generateSdkFixtures") {
    description = "Encode synthetic streams with the production adapters for SDK compatibility checks"
    group = "verification"
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.llmgateway.protocol.SdkFixtureGenerator")
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
    args(layout.buildDirectory.dir("sdk-fixtures").get().asFile.absolutePath)
}
