// S1mp1e 1.8.9（Ornithe）——照 Argentum 的建置設定：Loom 1.17.19 + Ploceus 1.17.6、Feather 對應表、Java 25。
// Argentum 沒有授權條款，只能讓啟動器從作者的發佈頁下載；這裡只在開發執行時從 libs/ 讀進來（libs/ 不進版控）。
plugins {
    id("java")
    id("net.fabricmc.fabric-loom-remap")
    id("ploceus")
}

fun v(n: String) = providers.gradleProperty("${n}_version").orNull ?: error("no property ${n}_version")

group = "dev.s1mp1e"
version = providers.gradleProperty("mod_version").get()

java.toolchain {
    languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // Ornithe 1.8.9 跑在 Java 25 上（和 Argentum 一樣），不降 release；降到 8 會讓 Gradle 變體解析
    // 拒絕 celeritas 這類標記「需要 JVM 17+」的執行期相依。
}

repositories {
    exclusiveContent {
        forRepository { mavenCentral() }
        filter { includeGroup("org.lwjgl") }
    }
    maven("https://maven.taumc.org/releases")
    maven("https://maven.cloverclient.com/releases")
    mavenCentral()
}

configurations.all {
    resolutionStrategy {
        exclude(group = "org.lwjgl.lwjgl")
    }
}

loom {
    uncompressNestedJars = true
    accessWidenerPath = file("src/main/resources/s1mp1e.accesswidener")
    runs.named("client") {
        // Argentum 原本的 -XstartOnFirstThread 只有 macOS 用，Windows 上 java 不認得，拿掉
        jvmArguments.addAll(
            "-XX:+UseZGC", "-XX:+UseCompactObjectHeaders",
            "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow"
        )
        runDirectory = layout.projectDirectory.dir("run")
    }
}

ploceus {
    setIntermediaryGeneration(2)
}

dependencies {
    minecraft("com.mojang:minecraft:${v("minecraft")}")
    mappings(loom.layered {
        mappings(ploceus.featherMappings(v("feather")))
        mappings(file("gradle/feather-overrides.tiny"))
    })

    modImplementation("net.fabricmc:fabric-loader:${v("fabric")}")
    ploceus.dependOsl(v("osl"))

    modImplementation("pl.tomgirl:pylon:${v("pylon")}")

    // 量測／相容測試用：Argentum 本體（作者發佈的 jar）。它正式運行時靠內嵌的 Celeritas/JOML nested jar
    // （Fabric JiJ 自動載），但 loom 的 dev runtime 不會把 files() 來源的 nested jar 解上 classpath，
    // 所以開發期要另外從 repo 補 celeritas（library mod）＋joml（純 lib）。release 已不降到 8，JVM 版本相容。
    if (file("libs/argentum-1.0.0.jar").isFile && !providers.gradleProperty("noArgentum").isPresent) {
        modRuntimeOnly(files("libs/argentum-1.0.0.jar"))
        modRuntimeOnly("org.embeddedt.celeritas:celeritas-common:${v("celeritas")}")
        runtimeOnly("org.joml:joml:1.10.5")
    }
}

tasks.processResources {
    val v = project.version
    inputs.property("version", v)
    filesMatching("fabric.mod.json") {
        expand("version" to v)
    }
}
