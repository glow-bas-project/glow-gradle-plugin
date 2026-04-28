package com.glow

import spock.lang.Specification
import spock.lang.TempDir
import org.gradle.testkit.runner.GradleRunner

class GlowServicePluginFunctionalTest extends Specification {
    @TempDir
    private File projectDir

    private getBuildFile() {
        new File(projectDir, "build.gradle")
    }

    private getSettingsFile() {
        new File(projectDir, "settings.gradle")
    }

    private void writeTestDockerfiles() {
        def dockerDir = new File(projectDir, "src/main/docker")
        dockerDir.mkdirs()
        new File(dockerDir, "Dockerfile.jvm") << "FROM scratch\n"
        new File(dockerDir, "Dockerfile.native") << "FROM scratch\n"
    }

    private void writeComposeEnv(File composeDir) {
        new File(composeDir, ".env") << """
REGISTRY_PREFIX=registry.example.invalid/glow
IMAGE_TAG=latest
POSTGRES_USER=glow
POSTGRES_PASSWORD=glow
"""
    }

    def "can run glowBuild task in dry-run mode without extension config"() {
        given:
        writeTestDockerfiles()
        settingsFile << "rootProject.name = 'billing-service'"
        def composeDir = new File(projectDir, "compose")
        composeDir.mkdirs()
        writeComposeEnv(composeDir)
        new File(composeDir, "docker-compose.yml") << """
services:
  glow-billing:
    image: \${SERVICE_IMAGE_REF}
"""
        buildFile << """
plugins {
    id('com.glow.local-env')
}

version = '1.8.3-SNAPSHOT'

glowLocalEnv {
    glowHome = projectDir.absolutePath
    devopsRoot = projectDir.absolutePath
    composeFile = "\${projectDir.absolutePath}/compose/docker-compose.yml"
    composeEnvFile = "\${projectDir.absolutePath}/compose/.env"
    registryImage = 'registry.example.invalid/glow/glow-billing-service'
    dryRun = true
}
"""

        when:
        def runner = GradleRunner.create()
        runner.forwardOutput()
        runner.withPluginClasspath()
        runner.withArguments("glowBuild")
        runner.withProjectDir(projectDir)
        def result = runner.build()

        then:
        result.output.contains("[glowBuild][DRY-RUN] build")
        result.output.contains("[glowBuild][DRY-RUN] compose")
        result.output.contains("Dockerfile.jvm")
        result.output.contains("registry.example.invalid/glow/glow-billing-service:local")
        result.output.contains("COMPOSE_SERVICE_NAME:glow-billing")
        result.output.contains("--force-recreate")
        result.output.contains("glow-billing")
        result.output.contains("--env-file")
    }

    def "supports native image type override"() {
        given:
        writeTestDockerfiles()
        settingsFile << "rootProject.name = 'orders-service'"
        def composeDir = new File(projectDir, "compose")
        composeDir.mkdirs()
        writeComposeEnv(composeDir)
        new File(composeDir, "docker-compose.yml") << """
services:
  glow-orders:
    image: \${SERVICE_IMAGE_REF}
"""
        buildFile << """
plugins {
    id('com.glow.local-env')
}

version = '2.4.0-SNAPSHOT'

glowLocalEnv {
    glowHome = projectDir.absolutePath
    devopsRoot = projectDir.absolutePath
    composeFile = "\${projectDir.absolutePath}/compose/docker-compose.yml"
    composeEnvFile = "\${projectDir.absolutePath}/compose/.env"
    dryRun = true
}
"""

        when:
        def result = GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withArguments("glowBuildImage", "-PglowImageType=native")
            .withProjectDir(projectDir)
            .build()

        then:
        result.output.contains("Dockerfile.native")
    }

}
