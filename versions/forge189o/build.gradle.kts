// S1mp1e Forge 相容層（1.8.9 Ornithe）：讓 Forge 1.8.9 模組在 Ornithe 上載入。
// Forge 1.8.9（LGPL-2.1）以二進位重映射後當函式庫：libs/s1forge-lib-named.jar（dev 編譯／執行）、
// libs/s1forge-lib-intermediary.jar（正式版 jar-in-jar）；Forge 加在原版類別上的成員由 DonorPlugin 在載入時移植。
// 這些檔案由 C:/Temp/s1port/forgetool（DonorGen＋package.sh）產生。
plugins {
    id("java")
    id("net.fabricmc.fabric-loom-remap")
    id("ploceus")
}

fun v(n: String) = providers.gradleProperty("${n}_version").orNull ?: error("no property ${n}_version")

group = "dev.s1mp1e"
version = providers.gradleProperty("mod_version").get()

java.toolchain { languageVersion = JavaLanguageVersion.of(25) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "5000"))
}

repositories {
    // libs/ 裡的 Forge 函式庫 jar（forgetool 產生）當成模組座標用，jar-in-jar 才包得進去
    flatDir { dirs("libs") }
    exclusiveContent {
        forRepository { mavenCentral() }
        filter { includeGroup("org.lwjgl") }
    }
    maven("https://maven.taumc.org/releases")
    maven("https://maven.cloverclient.com/releases")
    exclusiveContent {
        forRepository { maven("https://api.modrinth.com/maven") }
        filter { includeGroup("maven.modrinth") }
    }
    mavenCentral()
}

configurations.all { resolutionStrategy { exclude(group = "org.lwjgl.lwjgl") } }

loom {
    runs.named("client") {
        jvmArguments.addAll("-XX:+UseZGC", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow")
        runDirectory = layout.projectDirectory.dir("run")
    }
}

ploceus { setIntermediaryGeneration(2) }

dependencies {
    minecraft("com.mojang:minecraft:${v("minecraft")}")
    mappings(loom.layered {
        mappings(ploceus.featherMappings(v("feather")))
        mappings(file("gradle/feather-overrides.tiny"))
    })
    modImplementation("net.fabricmc:fabric-loader:${v("fabric")}")
    modImplementation("pl.tomgirl:pylon:${v("pylon")}")

    implementation(files("libs/s1forge-lib-named.jar"))
    include(":s1forge-lib-intermediary:11.15.1.2318")
    // Forge 1.8.9 用到的函式庫（原版沒有）
    implementation("javax.vecmath:vecmath:1.5.2")
    include("javax.vecmath:vecmath:1.5.2")
    implementation("net.sf.trove4j:trove4j:3.0.3")
    include("net.sf.trove4j:trove4j:3.0.3")

    // 事件橋接（S1mp1eBridge）編譯時要看到 S1mp1e 玻璃的事件類別；執行時有裝才會用
    modCompileOnly(files("../mc189o/build/libs/s1mp1e-1.8.9-ornithe-0.1.0.jar"))

    // 組合測試（-Pwith189o）：S1mp1e 玻璃（mc189o 的建置產物）＋Argentum 一起跑，驗證 Forge 相容層不干擾玻璃
    if (providers.gradleProperty("with189o").isPresent) {
        ploceus.dependOsl(v("osl"))
        modRuntimeOnly(files("../mc189o/build/libs/s1mp1e-1.8.9-ornithe-0.1.0.jar"))
        if (!providers.gradleProperty("noArgentum").isPresent) {
            modRuntimeOnly(files("../mc189o/libs/argentum-1.0.0.jar"))
            modRuntimeOnly("org.embeddedt.celeritas:celeritas-common:${v("celeritas")}")
            runtimeOnly("org.joml:joml:1.10.5")
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") { expand("version" to project.version) }
}
