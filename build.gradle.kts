plugins {
    id("uesugi.erii-plugin") version "1.0.0" apply false
}

tasks.register("assembleAllPlugins") {
    description = "Build all subproject plugins and collect into build/plugins/<id>/<id>.zip"

    subprojects.forEach { sub ->
        dependsOn(sub.tasks.named("pluginZip"))
    }

    doLast {
        val outputRoot = layout.buildDirectory.dir("plugins").get().asFile
        subprojects.forEach { sub ->
            val pluginId = sub.name
            val zipTask = sub.tasks.named("pluginZip", Zip::class.java).get()
            val sourceZip = zipTask.archiveFile.get().asFile
            val readmeFile = sub.file("README.md")
            val targetDir = outputRoot.resolve(pluginId)
            targetDir.mkdirs()
            sourceZip.copyTo(targetDir.resolve(sourceZip.name), overwrite = true)
            if (readmeFile.exists()) {
                readmeFile.copyTo(targetDir.resolve("README.md"), overwrite = true)
            }
        }
    }
}

tasks.register("buildAllPlugins") {
    description = "Build all subproject plugins and collect into build/plugins/<id>.zip"

    subprojects.forEach { sub ->
        dependsOn(sub.tasks.named("pluginZip"))
    }

    doLast {
        val outputRoot = layout.buildDirectory.dir("plugins").get().asFile
        subprojects.forEach { sub ->
            val zipTask = sub.tasks.named("pluginZip", Zip::class.java).get()
            val sourceZip = zipTask.archiveFile.get().asFile
            sourceZip.copyTo(outputRoot.resolve(sourceZip.name), overwrite = true)
        }
    }
}
