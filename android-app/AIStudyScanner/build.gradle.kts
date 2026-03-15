plugins {
    // Keep empty; plugins are applied in module build files.
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
