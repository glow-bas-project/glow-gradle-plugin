package com.glow

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

class GlowLocalEnvPlugin implements Plugin<Project> {
    static final String DEFAULT_REGISTRY_BASE = 'registry.gitlab.au.dk/backend-architecture-and-scalability'
    private static final String SERVICE_SUFFIX = '-service'
    private static final String GLOW_PREFIX = 'glow-'

    static String canonicalServiceName(String name) {
        if (name.endsWith(SERVICE_SUFFIX)) {
            return name.substring(0, name.length() - SERVICE_SUFFIX.length())
        }
        return name
    }

    static String composeServiceNameFromProject(String name) {
        def base = canonicalServiceName(name)
        if (!base.startsWith(GLOW_PREFIX)) {
            return "${GLOW_PREFIX}${base}"
        }
        return base
    }

    static String registryRepoFromProject(String name) {
        "${composeServiceNameFromProject(name)}${SERVICE_SUFFIX}"
    }

    @Deprecated
    static String registryImageName(String name) {
        registryRepoFromProject(name)
    }

    @Override
    void apply(Project project) {
        if (!project.providers.environmentVariable('GLOW_HOME').present) {
            project.logger.warn("GLOW_HOME is not set. glowBuild requires GLOW_HOME to resolve glow-devops compose path.")
        }
        def extension = project.extensions.create('glowLocalEnv', GlowLocalEnvExtension, project)

        project.tasks.register('glowBuildImage', GlowBuildImageTask) {
            description = 'Builds the local docker image for this service.'
            imageType.convention(extension.imageType)
            imageName.convention(extension.registryImage)
            imageTag.convention(extension.localImageTag)
            dryRun.convention(extension.dryRun)
            dockerfileDir.convention(extension.dockerfileDir)
        }

        project.tasks.register('glowComposeUp', GlowComposeUpTask) {
            description = 'Starts this service on the glow-devops compose stack.'
            composeFile.convention(extension.composeFile)
            composeEnvFile.convention(extension.composeEnvFile)
            composeServiceName.convention(extension.composeServiceName)
            imageSource.convention(extension.imageSource)
            registryImage.convention(extension.registryImage)
            dryRun.convention(extension.dryRun)
            serviceVersion.convention(extension.serviceVersion)
            localImageTag.convention(extension.localImageTag)
            additionalComposeArgs.convention(['--force-recreate'])
        }

        project.tasks.register('glowBuild') {
            group = 'glow'
            description = 'Builds a local Docker image and starts this service on the glow-devops stack.'
            dependsOn('glowBuildImage', 'glowComposeUp')
        }

        project.tasks.named('glowComposeUp').configure {
            mustRunAfter(project.tasks.named('glowBuildImage'))
        }

        project.plugins.withId('io.quarkus') {
            wireQuarkusBuild(project)
        }
        project.afterEvaluate {
            if (project.plugins.hasPlugin('io.quarkus')) {
                wireQuarkusBuild(project)
            }
        }
    }

    private static void wireQuarkusBuild(Project project) {
        def quarkusBuild = project.tasks.findByName('quarkusBuild')
        if (quarkusBuild == null) {
            return
        }
        project.tasks.named('glowBuildImage').configure {
            dependsOn(quarkusBuild)
        }
    }
}

class GlowLocalEnvExtension {
    final Property<String> glowHome
    final Property<String> devopsRoot
    final Property<String> composeFile
    final Property<String> composeEnvFile
    final Property<String> serviceName
    final Property<String> composeServiceName
    final Property<String> registryImage
    final Property<String> imageType
    final Property<String> imageSource
    final Property<String> registryBase
    final Property<String> dockerfileDir
    final Property<String> serviceVersion
    final Property<String> localImageTag
    final Property<Boolean> dryRun

    GlowLocalEnvExtension(Project project) {
        def providers = project.providers
        glowHome = project.objects.property(String)
        devopsRoot = project.objects.property(String)
        composeFile = project.objects.property(String)
        composeEnvFile = project.objects.property(String)
        serviceName = project.objects.property(String)
        composeServiceName = project.objects.property(String)
        registryImage = project.objects.property(String)
        imageType = project.objects.property(String)
        imageSource = project.objects.property(String)
        registryBase = project.objects.property(String)
        dockerfileDir = project.objects.property(String)
        serviceVersion = project.objects.property(String)
        localImageTag = project.objects.property(String)
        dryRun = project.objects.property(Boolean)

        serviceName.convention(project.name)
        composeServiceName.convention(serviceName.map { GlowLocalEnvPlugin.composeServiceNameFromProject(it) })
        glowHome.convention(providers.environmentVariable('GLOW_HOME'))
        devopsRoot.convention(glowHome.map { "${it}/glow-devops" })
        composeFile.convention(devopsRoot.map { "${it}/compose/docker-compose.yml" })
        composeEnvFile.convention(devopsRoot.map { "${it}/compose/.env" })
        serviceVersion.convention(project.provider { project.version.toString() })
        imageType.convention(providers.gradleProperty('glowImageType').orElse('jvm'))
        imageSource.convention(providers.gradleProperty('glowImageSource').orElse('localBuilt'))
        registryBase.convention(
            providers.gradleProperty('glowRegistryBase')
                .orElse(providers.environmentVariable('CI_REGISTRY'))
                .orElse(GlowLocalEnvPlugin.DEFAULT_REGISTRY_BASE)
        )
        registryImage.convention(
            providers.environmentVariable('CI_REGISTRY_IMAGE')
                .orElse(
                    registryBase.zip(serviceName) { base, service ->
                        "${base}/${GlowLocalEnvPlugin.registryRepoFromProject(service)}"
                    }
                )
        )
        dockerfileDir.convention(
            providers.gradleProperty('glowDockerfileDir')
                .orElse('src/main/docker')
        )
        localImageTag.convention(providers.gradleProperty('glowLocalTag').orElse('local'))
        dryRun.convention(providers.gradleProperty('glowDryRun').map { it.toBoolean() }.orElse(false))
    }
}

abstract class GlowBuildImageTask extends DefaultTask {
    @Inject
    abstract ExecOperations getExecOperations()

    @Input
    abstract Property<String> getImageType()
    @Input
    abstract Property<String> getImageName()
    @Input
    abstract Property<String> getImageTag()
    @Input
    abstract Property<Boolean> getDryRun()
    @Input
    abstract Property<String> getDockerfileDir()

    @TaskAction
    void run() {
        def type = imageType.get()
        if (!['jvm', 'native'].contains(type)) {
            throw new GradleException("Unsupported glow image type '${type}'. Supported values: jvm, native.")
        }

        def dockerfile = "${dockerfileDir.get()}/Dockerfile.${type}"
        def dockerFileRef = new File(project.projectDir, dockerfile)
        if (!dockerFileRef.exists()) {
            throw new GradleException("Dockerfile not found at '${dockerFileRef.absolutePath}'. Configure glowLocalEnv.dockerfileDir or place Dockerfile.${type} under src/main/docker.")
        }

        if (!dryRun.get()) {
            def quarkusAppDir = new File(project.projectDir, 'build/quarkus-app')
            if (!quarkusAppDir.isDirectory()) {
                throw new GradleException(
                    "Missing ${quarkusAppDir.absolutePath}. The Docker image expects Quarkus build output.\n" +
                    "Run ./gradlew quarkusBuild (or ./gradlew glowBuild, which runs quarkusBuild when the Quarkus plugin is applied)."
                )
            }
        }

        def command = ['docker', 'build', '-f', dockerfile, '-t', "${imageName.get()}:${imageTag.get()}", '.']

        if (dryRun.get()) {
            logger.lifecycle("[glowBuild][DRY-RUN] build ${command.join(' ')}")
            return
        }

        execOperations.exec { execSpec ->
            execSpec.commandLine(command)
        }
    }
}

abstract class GlowComposeUpTask extends DefaultTask {
    @Inject
    abstract ExecOperations getExecOperations()

    @Input
    abstract Property<String> getComposeFile()
    @Input
    abstract Property<String> getComposeEnvFile()
    @Input
    abstract Property<String> getComposeServiceName()
    @Input
    abstract Property<String> getImageSource()
    @Input
    abstract Property<String> getRegistryImage()
    @Input
    abstract Property<Boolean> getDryRun()
    @Input
    abstract Property<String> getServiceVersion()
    @Input
    abstract Property<String> getLocalImageTag()
    @Input
    abstract ListProperty<String> getAdditionalComposeArgs()

    GlowComposeUpTask() {
        additionalComposeArgs.convention([])
    }

    @TaskAction
    void run() {
        if (!composeFile.present) {
            throw new GradleException("Compose file is not configured because GLOW_HOME is not set. Export GLOW_HOME to your repos root (the parent folder containing glow-devops and service repos), or configure glowLocalEnv.composeFile explicitly.")
        }
        def file = new File(composeFile.get())
        if (!file.exists()) {
            throw new GradleException("Compose file not found at '${file.absolutePath}'. Configure glowLocalEnv.composeFile or set GLOW_HOME correctly.")
        }

        def envFile = GlowComposeEnv.requiredEnvFile(composeEnvFile.get())
        def composeEnv = GlowComposeEnv.loadEnvFile(envFile)

        def source = imageSource.get()
        if (!['registryLatest', 'localBuilt', 'registryVersioned'].contains(source)) {
            throw new GradleException("Unsupported glow image source '${source}'. Supported values: registryLatest, localBuilt, registryVersioned.")
        }

        def tag = source == 'registryLatest' ? 'latest' : (source == 'registryVersioned' ? serviceVersion.get() : localImageTag.get())
        def imageRef = "${registryImage.get()}:${tag}"
        def env = new LinkedHashMap<String, String>(composeEnv)
        env['SERVICE_IMAGE_REF'] = imageRef
        env['COMPOSE_SERVICE_NAME'] = composeServiceName.get()

        def command = [
            'docker', 'compose',
            '--env-file', envFile.absolutePath,
            '-f', file.absolutePath,
            'up', '-d'
        ] + additionalComposeArgs.get() + [composeServiceName.get()]

        if (dryRun.get()) {
            logger.lifecycle("[glowBuild][DRY-RUN] compose env=${env} cmd=${command.join(' ')}")
            return
        }

        execOperations.exec { execSpec ->
            execSpec.environment(env)
            execSpec.commandLine(command)
        }
    }
}

class GlowComposeEnv {
    private static final List<String> REQUIRED_KEYS = [
        'POSTGRES_USER',
        'POSTGRES_PASSWORD',
        'REGISTRY_PREFIX',
        'IMAGE_TAG'
    ]

    static File requiredEnvFile(String path) {
        def envFile = new File(path)
        if (!envFile.exists()) {
            def example = new File(envFile.parentFile, '.env.example')
            throw new GradleException(
                "Missing compose env file: ${envFile.absolutePath}\n" +
                "Create it from the template:\n" +
                "  cp ${example.absolutePath} ${envFile.absolutePath}\n" +
                "Then set at least POSTGRES_USER, POSTGRES_PASSWORD, REGISTRY_PREFIX, and IMAGE_TAG."
            )
        }
        return envFile
    }

    static Map<String, String> loadEnvFile(File envFile) {
        def values = [:] as LinkedHashMap<String, String>
        envFile.eachLine { line ->
            def trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) {
                return
            }
            def separator = trimmed.indexOf('=')
            if (separator <= 0) {
                return
            }
            def key = trimmed.substring(0, separator).trim()
            def value = trimmed.substring(separator + 1).trim()
            values[key] = value
        }

        def missing = REQUIRED_KEYS.findAll { key -> !values[key]?.trim() }
        if (!missing.isEmpty()) {
            throw new GradleException(
                "Missing or empty variables in ${envFile.absolutePath}: ${missing.join(', ')}"
            )
        }
        return values
    }
}
