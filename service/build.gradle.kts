plugins {
    alias(libs.plugins.spring.boot) apply false
}

val bootBom = libs.spring.boot.bom
val modulithBom = libs.spring.modulith.bom
val modulithApi = libs.spring.modulith.api

subprojects {
    apply(plugin = "java-library")

    group = "kr.team2.portfolio"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    dependencies {
        // 모든 모듈이 같은 버전을 쓰도록 BOM을 공유한다
        "implementation"(platform(bootBom))
        "implementation"(platform(modulithBom))
        "annotationProcessor"(platform(bootBom))
        "testImplementation"(platform(bootBom))
        "testImplementation"(platform(modulithBom))
        // @ApplicationModule, @NamedInterface 등 모듈 경계 표시용
        "implementation"(modulithApi)
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
}
