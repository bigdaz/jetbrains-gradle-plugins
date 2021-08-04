package org.jetbrains.gradle.plugins.docker.tasks

import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Input
import org.gradle.kotlin.dsl.getValue
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.setValue

class DockerExecLogin : AbstractDockerExecTask() {

    init {
        logging.captureStandardOutput(LogLevel.INFO)
        logging.captureStandardError(LogLevel.INFO)
    }

    @get:Input
    var username: String by project.objects.property()

    @get:Input
    var password: String by project.objects.property()

    @get:Input
    var url: String by project.objects.property()

    override fun provideArguments() =
        listOf("login", "-u", username, "-p", password, url)
}