plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.11.0"
    id("com.github.node-gradle.node") version "7.0.2" // 웹앱 빌드 자동화용
}

group = "net.ib.ixpert.ops.wuwagent"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.3.5")
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        pluginVerifier()
        zipSigner()
    }
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
        if (name.contains("Test")) {
            enabled = false
        }
    }

    patchPluginXml {
        sinceBuild.set("243")
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
