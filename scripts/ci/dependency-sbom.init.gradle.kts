import groovy.json.JsonOutput
import java.net.URLEncoder
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.UnresolvedDependencyResult

// Works with the main build, standalone consumers, and an immutable release checkout.
gradle.projectsEvaluated {
    rootProject.tasks.register("dependencySbom") {
        group = "verification"
        description = "Export resolved production dependencies as a CycloneDX inventory"
        val report = rootProject.layout.buildDirectory.file("reports/security/runtime.cdx.json")
        outputs.file(report)
        outputs.upToDateWhen { false }
        doLast {
            val modules = sortedMapOf<String, Map<String, String>>()
            rootProject.allprojects.forEach projectLoop@ { project ->
                val runtime = project.configurations.findByName("runtimeClasspath") ?: return@projectLoop
                val resolution = runtime.incoming.resolutionResult
                val unresolved = resolution.allDependencies.filterIsInstance<UnresolvedDependencyResult>()
                check(unresolved.isEmpty()) { "Cannot scan an unresolved dependency graph in ${project.path}: $unresolved" }
                resolution.allComponents.forEach componentLoop@ { component ->
                    val id = component.id as? ModuleComponentIdentifier ?: return@componentLoop
                    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
                    val purl = "pkg:maven/${encode(id.group)}/${encode(id.module)}@${encode(id.version)}"
                    modules[purl] = mapOf("type" to "library", "group" to id.group,
                        "name" to id.module, "version" to id.version, "purl" to purl, "bom-ref" to purl)
                }
            }
            check(modules.isNotEmpty()) { "No resolved production dependencies found" }
            val output = report.get().asFile
            output.parentFile.mkdirs()
            output.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(mapOf(
                "bomFormat" to "CycloneDX", "specVersion" to "1.6", "version" to 1,
                "components" to modules.values.toList()
            ))) + "\n")
            logger.lifecycle("Exported ${modules.size} resolved components to $output")
        }
    }
}
