package com.glow

import org.gradle.api.Project
import org.gradle.api.Plugin

class GlowGradlePluginPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.tasks.register("greeting") {
            doLast {
                println("Hello from plugin 'com.glow.greeting'")
            }
        }
    }
}
