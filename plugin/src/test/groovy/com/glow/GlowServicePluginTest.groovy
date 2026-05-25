package com.glow

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class GlowServicePluginTest extends Specification {
    def "plugin registers glowBuild and internal tasks"() {
        given:
        def project = ProjectBuilder.builder().build()

        when:
        project.plugins.apply("com.glow.local-env")

        then:
        project.tasks.findByName("glowBuild") != null
        project.tasks.findByName("glowBuildImage") != null
        project.tasks.findByName("glowComposeUp") != null
        project.tasks.findByName("glowLocalEnv") == null
    }

    def "plugin maps menu-service to glow-menu compose key"() {
        given:
        def project = ProjectBuilder.builder().withName("menu-service").build()
        project.version = "1.2.3-SNAPSHOT"

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.serviceName.get() == "menu-service"
        extension.composeServiceName.get() == "glow-menu"
        extension.localImageTag.get() == "local"
        extension.imageSource.get() == "localBuilt"
        GlowLocalEnvPlugin.registryRepoFromProject("menu-service") == "glow-menu-service"
    }

    def "plugin maps restaurant to glow-restaurant compose key"() {
        given:
        def project = ProjectBuilder.builder().withName("restaurant").build()

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.composeServiceName.get() == "glow-restaurant"
        GlowLocalEnvPlugin.registryRepoFromProject("restaurant") == "glow-restaurant-service"
    }

    def "plugin maps glow-restaurant-service to glow-restaurant compose key"() {
        given:
        def project = ProjectBuilder.builder().withName("glow-restaurant-service").build()

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.composeServiceName.get() == "glow-restaurant"
        GlowLocalEnvPlugin.registryRepoFromProject("glow-restaurant-service") == "glow-restaurant-service"
    }

    def "plugin keeps glow-restaurant compose key when project has no suffix"() {
        given:
        def project = ProjectBuilder.builder().withName("glow-restaurant").build()

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.composeServiceName.get() == "glow-restaurant"
        GlowLocalEnvPlugin.registryRepoFromProject("glow-restaurant") == "glow-restaurant-service"
    }

    def "registryImage convention prefers CI_REGISTRY_IMAGE when set"() {
        given:
        def ciRegistryImage = System.getenv("CI_REGISTRY_IMAGE")
        if (!ciRegistryImage) {
            return
        }

        def project = ProjectBuilder.builder().withName("menu-service").build()

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.registryImage.get() == ciRegistryImage
    }

    def "registryImage convention uses registry base and service repo when CI_REGISTRY_IMAGE is unset"() {
        given:
        if (System.getenv("CI_REGISTRY_IMAGE")) {
            return
        }

        def project = ProjectBuilder.builder().withName("menu-service").build()

        when:
        project.plugins.apply("com.glow.local-env")
        def extension = project.extensions.getByName("glowLocalEnv")

        then:
        extension.registryImage.get() ==
            "${GlowLocalEnvPlugin.DEFAULT_REGISTRY_BASE}/glow-menu-service"
    }
}
