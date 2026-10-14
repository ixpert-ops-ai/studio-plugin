plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("com.github.node-gradle.node") version "7.0.2" // 웹앱 빌드 자동화용
}

group = "net.ib.ixpert.ops.wuwagent"

// 빌드 대상 플랫폼: 기본(미지정)=기존 빌드(since-build 243), -Pplatform=253 → IntelliJ 2026.2용
// (2026.2부터 JCEF가 별도 모듈로 분리되어 plugin.xml에 jcef depends 필요. 2025.3.1 미만에서는 인식 불가)
val targetPlatform: String? = providers.gradleProperty("platform").orNull
val is253 = targetPlatform == "253"
if (targetPlatform != null && !is253) {
    throw GradleException("지원하지 않는 -Pplatform 값: '$targetPlatform' (지원: 253, 미지정 시 기존 빌드)")
}
version = if (is253) "1.0-SNAPSHOT-253" else "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.5")
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.jetbrains.kotlin")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Plugin.Java)
        pluginVerifier()
        zipSigner()
    }
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

// Node.js 자동 다운로드 및 설정
node {
    version.set("20.11.1")
    download.set(true)
    workDir.set(layout.buildDirectory.dir("nodejs").get().asFile)
    npmWorkDir.set(layout.buildDirectory.dir("npm").get().asFile)
    yarnWorkDir.set(layout.buildDirectory.dir("yarn").get().asFile)
    nodeProjectDir.set(file("${project.rootDir}/webview"))
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set(if (is253) "253" else "243")

        // platform 값이 바뀌면 캐시(UP-TO-DATE/FROM-CACHE)를 재사용하지 않도록 input으로 선언
        inputs.property("targetPlatform", targetPlatform ?: "default")

        // 253 빌드일 때만 패치된 plugin.xml(빌드 산출물)에 jcef depends 주입. 원본 소스는 수정하지 않음
        if (is253) {
            doLast {
                val xml = outputFile.get().asFile
                val anchor = "<depends>com.intellij.modules.java</depends>"
                val jcef = "<depends>com.intellij.modules.jcef</depends>"
                val text = xml.readText()
                if (!text.contains(jcef)) {
                    check(text.contains(anchor)) { "plugin.xml에서 depends 기준 라인을 찾지 못했습니다" }
                    xml.writeText(text.replace(anchor, "$anchor\n    $jcef"))
                }
            }
        }
    }

    // 웹앱 의존성 설치
    val installWebviewDependencies by registering(com.github.gradle.node.npm.task.NpmTask::class) {
        args.set(listOf("install"))
        dependsOn("npmSetup")
        inputs.file("webview/package.json")
        outputs.dir("webview/node_modules")
    }

    // 웹앱 빌드
    val buildWebview by registering(com.github.gradle.node.npm.task.NpmTask::class) {
        args.set(listOf("run", "build"))
        dependsOn(installWebviewDependencies)
        inputs.dir("webview/src")
        inputs.file("webview/package.json")
        inputs.file("webview/vite.config.ts")
        outputs.dir(layout.buildDirectory.dir("webview").get().asFile) // vite outDir 매칭
    }

    // 빌드된 웹앱을 플러그인 리소스에 복사 (JCEF가 읽을 수 있도록)
    val copyWebviewToResources by registering(Copy::class) {
        dependsOn(buildWebview)
        from(layout.buildDirectory.dir("webview").get().asFile)
        into("${project.projectDir}/src/main/resources/webview")
    }

    processResources {
        dependsOn(copyWebviewToResources)
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    // 빌드된 플러그인 압축파일(.zip)을 _release 폴더로 복사 (현재 프로젝트명 파일만)
    val copyToRelease by registering(Copy::class) {
        dependsOn("buildPlugin")
        from(layout.buildDirectory.dir("distributions").get().asFile) {
            include("${rootProject.name}-*.zip")
        }
        into("${project.rootDir}/_release")
    }

    // 기본 build 태스크가 끝날 때 항상 copyToRelease를 실행하도록 설정
    named("build") {
        finalizedBy(copyToRelease)
    }
}
