plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
dependencies { testImplementation("junit:junit:4.13.2") }
tasks.test { maxHeapSize = "256m" }

tasks.register<JavaExec>("largeFileBenchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.hycnyl.easybase64.core.LargeFileBenchmark")
    maxHeapSize = "256m"
    args(layout.buildDirectory.dir("large-file-benchmark").get().asFile.absolutePath)
}
