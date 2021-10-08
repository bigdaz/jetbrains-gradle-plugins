package org.jetbrains.gradle.plugins.terraform

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.component.SoftwareComponentFactory
import org.gradle.api.distribution.DistributionContainer
import org.gradle.api.file.CopySpec
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.file.RelativePath
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.*
import org.jetbrains.gradle.plugins.executeAllOn
import org.jetbrains.gradle.plugins.maybeRegister
import org.jetbrains.gradle.plugins.terraform.tasks.*
import org.jetbrains.gradle.plugins.toCamelCase

internal class TFTaskContainer private constructor(
    private val taskName: String,
    private val terraformModuleMetadata: TaskProvider<GenerateTerraformMetadata>,
    private val terraformModuleZip: Zip,
    private val copyResFiles: TaskProvider<CopyTerraformResourceFileInModules>,
    private val createResFile: TaskProvider<GenerateResourcesTerraformFile>,
    private val copyExecutionContext: TaskProvider<Sync>,
    private val syncLockFile: TaskProvider<Copy>,
    private val tfInit: TaskProvider<TerraformInit>,
    private val tfShow: TaskProvider<TerraformShow>,
    private val tfPlan: TaskProvider<TerraformPlan>,
    private val tfApply: TaskProvider<TerraformApply>,
    private val tfDestroyShow: TaskProvider<TerraformShow>,
    private val tfDestroyPlan: TaskProvider<TerraformPlan>,
    private val tfDestroy: TaskProvider<TerraformApply>,
    private val terraformImplementation: Configuration,
    private val lambdaConfiguration: Configuration,
    private val terraformExtension: TerraformExtension,
    private val sourceSet: TerraformSourceSet,
    private val project: Project
) {

    companion object {
        operator fun invoke(
            project: Project,
            sourceSet: TerraformSourceSet,
            terraformApi: Configuration,
            terraformImplementation: Configuration,
            lambdaConfiguration: Configuration,
            terraformExtract: TaskProvider<TerraformExtract>,
            terraformExtension: TerraformExtension,
            terraformInit: TaskProvider<Task>,
            terraformShow: TaskProvider<Task>,
            terraformPlan: TaskProvider<Task>,
            terraformApply: TaskProvider<Task>,
            terraformDestroyShow: TaskProvider<Task>,
            terraformDestroyPlan: TaskProvider<Task>,
            terraformDestroy: TaskProvider<Task>,
            softwareComponentFactory: SoftwareComponentFactory
        ): TFTaskContainer = with(project) {
            val taskName = sourceSet.name.capitalize()
            val terraformModuleMetadata =
                tasks.register<GenerateTerraformMetadata>("terraform${taskName}Metadata")

            val terraformModuleZip = tasks.create<Zip>("terraform${taskName}Module")

            if (sourceSet.name == "main")
                createComponent(taskName, terraformApi, terraformModuleZip, sourceSet, softwareComponentFactory)

            val copyResFiles =
                tasks.register<CopyTerraformResourceFileInModules>("copy${taskName}ResFileInExecutionContext")

            val createResFile =
                tasks.register<GenerateResourcesTerraformFile>("generate${taskName}ResFile")

            val copyExecutionContext = tasks.register<Sync>("generate${taskName}ExecutionContext")

            val syncLockFile = tasks.register<Copy>("sync${taskName}LockFile")

            plugins.withId("org.gradle.distribution") {
                configure<DistributionContainer> {
                    maybeRegister(sourceSet.name) {
                        contents {
                            from(copyExecutionContext)
                            from(terraformExtract) {
                                rename {
                                    buildString {
                                        append("terraform")
                                        if (OperatingSystem.current().isWindows)
                                            append(".exe")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val tfInit: TaskProvider<TerraformInit> = tasks.register<TerraformInit>("terraform${taskName}Init")

            terraformInit { dependsOn(tfInit) }
            val tfShow: TaskProvider<TerraformShow> = tasks.register<TerraformShow>("terraform${taskName}Show")

            terraformShow { dependsOn(tfShow) }
            val tfPlan: TaskProvider<TerraformPlan> = tasks.register<TerraformPlan>("terraform${taskName}Plan")

            terraformPlan { dependsOn(tfPlan) }
            tfShow { dependsOn(tfPlan) }

            val tfApply: TaskProvider<TerraformApply> = tasks.register<TerraformApply>("terraform${taskName}Apply")
            terraformApply { dependsOn(tfApply) }

            val tfDestroyShow: TaskProvider<TerraformShow> =
                tasks.register<TerraformShow>("terraform${taskName}DestroyShow")
            terraformDestroyShow { dependsOn(tfDestroyShow) }

            val tfDestroyPlan: TaskProvider<TerraformPlan> =
                tasks.register<TerraformPlan>("terraform${taskName}DestroyPlan")

            terraformDestroyPlan { dependsOn(tfDestroyPlan) }
            tfDestroyShow { dependsOn(tfDestroyPlan) }

            val tfDestroy: TaskProvider<TerraformApply> = tasks.register<TerraformApply>("terraform${taskName}Destroy")
            terraformDestroy { dependsOn(tfDestroy) }

            return TFTaskContainer(
                taskName, terraformModuleMetadata, terraformModuleZip, copyResFiles, createResFile,
                copyExecutionContext, syncLockFile, tfInit, tfShow, tfPlan, tfApply, tfDestroyShow,
                tfDestroyPlan, tfDestroy, terraformImplementation, lambdaConfiguration,
                terraformExtension, sourceSet, project
            )
        }
    }

    fun configureTasksForSourceSet() = with(project) {
        terraformModuleMetadata {
            outputFile = file("${sourceSet.baseBuildDir}/tmp/metadata.json")
            metadata = sourceSet.metadata
        }

        fun CopySpec.sourcesCopySpec(action: CopySpec.() -> Unit = {}) {
            from(sourceSet.getSourceDependencies().flatMap { it.srcDirs } + sourceSet.srcDirs) {
                action()
                include { it.file.extension == "tf" || it.isDirectory }
            }
            from(sourceSet.getSourceDependencies().flatMap { it.resourcesDirs } + sourceSet.resourcesDirs) {
                into("resources")
            }
            if (sourceSet.addLambdasToResources) from(lambdaConfiguration) {
                into("resources")
            }
        }

        terraformModuleZip {
            from(terraformModuleMetadata)
            sourcesCopySpec {
                into("src/${sourceSet.metadata.group}/${sourceSet.metadata.moduleName}")
            }
            includeEmptyDirs = false
            exclude { it.file.endsWith(".terraform.lock.hcl") }
            duplicatesStrategy = DuplicatesStrategy.WARN
            archiveFileName.set("terraform${project.name.toCamelCase().capitalize()}${taskName}.tfmodule")
            destinationDirectory.set(file("$buildDir/terraform/archives"))
        }

        val terraformRuntimeElements =
            configurations.create("terraform${taskName}RuntimeElements") {
                isCanBeResolved = true
                isCanBeConsumed = false
                extendsFrom(terraformImplementation)
                attributes {
                    attribute(Usage.USAGE_ATTRIBUTE, objects.named(TerraformPlugin.Attributes.USAGE))
                    attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        objects.named(TerraformPlugin.Attributes.LIBRARY_ELEMENTS)
                    )
                }
            }

        val tmpRestFile = file("${sourceSet.baseBuildDir}/tmp/res.tf")

        copyResFiles {
            onlyIf { tmpRestFile.exists() }
            inputResFile = tmpRestFile
            runtimeContextDir = sourceSet.runtimeExecutionDirectory
        }

        createResFile {
            val resDir = sourceSet.runtimeExecutionDirectory.resolve("resources")
            onlyIf { resDir.exists() }
            resourcesDirectory = resDir
            outputResourceModuleFile = tmpRestFile
            finalizedBy(copyResFiles)
        }

        copyExecutionContext {
            dependsOn(terraformRuntimeElements)

            fun sourcesSpec(toDrop: Int): Action<CopySpec> = Action {

                eachFile {
                    if (relativePath.segments.first() == "src") {
                        relativePath = RelativePath(
                            true,
                            *relativePath.segments.drop(toDrop).toTypedArray()
                        )
                    }
                }
            }

            from(terraformRuntimeElements.resolve().map { zipTree(it) }, sourcesSpec(1))
            from(terraformModuleMetadata)
            sourcesCopySpec()
            exclude { it.name == "metadata.json" }
            includeEmptyDirs = false
            from(sourceSet.lockFile)
            into(sourceSet.runtimeExecutionDirectory)
            finalizedBy(createResFile)
        }

        syncLockFile {
            from(sourceSet.runtimeExecutionDirectory.resolve(".terraform.lock.hcl"))
            into(sourceSet.lockFile.parentFile)
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }

        tfInit {
            dependsOn(copyExecutionContext)
            attachSourceSet(sourceSet)
            finalizedBy(syncLockFile)
            sourceSet.tasksProvider.initActions.executeAllOn(this)
            if (terraformExtension.showInitOutputInConsole)
                logging.captureStandardOutput(LogLevel.LIFECYCLE)
        }

        tfShow {
            attachSourceSet(sourceSet)
            inputPlanFile = sourceSet.outputBinaryPlan
            outputJsonPlanFile = sourceSet.outputJsonPlan
            sourceSet.tasksProvider.showActions.executeAllOn(this)
        }

        tfPlan {
            dependsOn(tfInit)
            attachSourceSet(sourceSet)
            outputPlanFile = sourceSet.outputBinaryPlan
            variables = sourceSet.planVariables
            finalizedBy(tfShow)
            sourceSet.tasksProvider.planActions.executeAllOn(this)
            if (terraformExtension.showPlanOutputInConsole)
                logging.captureStandardOutput(LogLevel.LIFECYCLE)
        }

        tfApply {
            attachSourceSet(sourceSet)
            configureApply(
                tfPlan,
                sourceSet.applySpec,
                sourceSet.outputBinaryPlan,
                sourceSet.tasksProvider.applyActions
            )
        }

        sourceSet.outputTasks.configureEach {
            dependsOn(tfInit)
            attachSourceSet(sourceSet)
            val fileName = variables.joinToString("") { it.toCamelCase().capitalize() }.decapitalize()
            val extension = when (format) {
                TerraformOutput.Format.JSON -> ".json"
                TerraformOutput.Format.RAW -> ".txt"
            }
            outputFile = file("${sourceSet.baseBuildDir}/outputs/$fileName.$extension")
        }

        tfDestroyShow {
            attachSourceSet(sourceSet)
            inputPlanFile = sourceSet.outputBinaryPlan
            outputJsonPlanFile = sourceSet.outputDestroyJsonPlan
            sourceSet.tasksProvider.destroyShowActions.executeAllOn(this)
        }

        tfDestroyPlan {
            attachSourceSet(sourceSet)
            dependsOn(tfInit)
            outputPlanFile = sourceSet.outputDestroyBinaryPlan
            isDestroy = true
            variables = sourceSet.planVariables
            finalizedBy(tfDestroyShow)
            sourceSet.tasksProvider.destroyPlanActions.executeAllOn(this)
            if (terraformExtension.showPlanOutputInConsole)
                logging.captureStandardOutput(LogLevel.LIFECYCLE)
        }

        tfDestroy {
            attachSourceSet(sourceSet)
            configureApply(
                tfDestroyPlan,
                sourceSet.destroySpec,
                sourceSet.outputDestroyBinaryPlan,
                sourceSet.tasksProvider.destroyActions
            )
        }

    }
}

