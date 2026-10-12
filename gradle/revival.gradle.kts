// RevivalPaper build additions, applied to :leaf-server from the root build script.
// They live here instead of in leaf-server/build.gradle.kts.patch so Leaf updates never conflict with them.

// Every @RevivalTest under gg.hcfrevival.revival, run through RevivalTestSuite: ./gradlew :leaf-server:runRevivalTests
tasks.register<Test>("runRevivalTests") {
    group = "verification"
    description = "Runs the RevivalPaper test suite"
    val test = tasks.named<Test>("test").get()
    testClassesDirs = test.testClassesDirs
    classpath = test.classpath
    jvmArgumentProviders.addAll(test.jvmArgumentProviders) // Paper's mockito agent
    include("**/RevivalTestSuite.class")
    workingDir = temporaryDir
    useJUnitPlatform()
}

// Brand the server jar. Registered after Leaf's manifest block so these values win: ServerBuildInfo.brandId()
// reports hcfrevival:revivalpaper and Bukkit.getName() reports RevivalPaper.
afterEvaluate {
    tasks.named<Jar>("jar") {
        manifest {
            attributes(
                "Implementation-Title" to "RevivalPaper",
                "Specification-Title" to "RevivalPaper",
                "Specification-Vendor" to "hcfrevival",
                "Brand-Id" to "hcfrevival:revivalpaper",
                "Brand-Name" to "RevivalPaper",
            )
        }
    }
}

// Live-server tests: a plugin that scripts/revival/live-tests.py drops into a throwaway server. It compiles against the
// server itself (Minecraft, Paper and RevivalPaper classes), so it can drive fake players through real packet handlers.
val revivalSourceSets = the<SourceSetContainer>()
val revivalLiveTest = revivalSourceSets.create("revivalLiveTest") { // src/revivalLiveTest/{java,resources}
    compileClasspath += revivalSourceSets["main"].output + revivalSourceSets["main"].compileClasspath
}
tasks.register<Jar>("revivalLiveTestPlugin") {
    group = "verification"
    description = "Builds the RevivalPaper live-test plugin (leaf-server/build/libs/revival-live-tests.jar)"
    from(revivalLiveTest.output)
    archiveFileName.set("revival-live-tests.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}
