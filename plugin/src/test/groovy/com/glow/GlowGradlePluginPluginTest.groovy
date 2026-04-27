package com.glow

import org.gradle.testfixtures.ProjectBuilder
import org.gradle.api.Project
import spock.lang.Specification

class GlowGradlePluginPluginTest extends Specification {
    def "plugin registers task"() {
        given:
        def project = ProjectBuilder.builder().build()

        when:
        project.plugins.apply("com.glow.greeting")

        then:
        project.tasks.findByName("greeting") != null
    }
}
