package net.ib.ixpert.ops.wuwagent.service.metagraph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifier
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.time.Instant
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.vfs.VirtualFile

/**
 * 프로젝트 전체 메타 그래프 빌더 (오케스트레이터).
 *
 * 실행 흐름 (Step 1~9):
 * 1. Dumb Mode 체크
 * 2. 멀티모듈 소스 루트 탐색 → PsiJavaFile 목록 수집
 * 3. 파일별 ReadAction으로 PsiClass 분석 → FileNode 생성
 * 4. 전체 인터페이스 타입 수집 → 배치 ClassInheritorsSearch
 * 5. DependencyInjection.resolvedImpl 채우기
 * 6. 양방향 dependsOn/dependedBy 채우기
 * 7. Relationship 리스트 생성
 * 8. GraphStatistics 계산
 * 9. JSON 출력
 */
class ProjectGraphBuilder(private val project: Project, private val targetDirectory: VirtualFile? = null) {

    companion object {
        const val LOW_THRESHOLD = 0
        const val MEDIUM_THRESHOLD = 1
        const val HIGH_THRESHOLD = 2
        const val CRITICAL_THRESHOLD = 3
    }

    private val logger = Logger.getInstance(ProjectGraphBuilder::class.java)
    private val annotationResolver = SpringAnnotationResolver()
    private val dependencyResolver = DependencyResolver(project)
    private val exporter = MetaGraphExporter()
    private val endpointAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.SpringEndpointAnalyzer()
    private val beanAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.SpringBeanAnalyzer()
    private val entityAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.JpaEntityAnalyzer()
    private val callRelationAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.CallRelationAnalyzer()
    private val anyframeServiceIdAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.AnyframeServiceIdAnalyzer()
    private val anyframeDemAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.AnyframeDemAnalyzer()
    private val anyframeDependencyAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.AnyframeDependencyAnalyzer()
    private val anyframeBizCallAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.AnyframeBizCallAnalyzer()
    private val anyframeVoChainAnalyzer = net.ib.ixpert.ops.wuwagent.service.metagraph.analyzer.AnyframeVoChainAnalyzer()

    /**
     * 비동기로 프로젝트 그래프를 생성합니다.
     *
     * @param onProgress 진행 상태 메시지 콜백 (UI 갱신용)
     * @param onComplete 완료 시 결과 콜백
     * @param onError 에러 시 콜백
     */
    fun buildGraphAsync(
        onProgress: ((String) -> Unit)? = null,
        onComplete: (ProjectGraph) -> Unit,
        onError: (String) -> Unit
    ) {
        // Step 1: Dumb Mode 체크
        if (DumbService.isDumb(project)) {
            onProgress?.invoke("인덱싱 완료를 기다리는 중...")
            DumbService.getInstance(project).runWhenSmart {
                executeBuild(onProgress, onComplete, onError)
            }
            return
        }
        executeBuild(onProgress, onComplete, onError)
    }

    private fun executeBuild(
        onProgress: ((String) -> Unit)?,
        onComplete: (ProjectGraph) -> Unit,
        onError: (String) -> Unit
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Generating Project Meta Graph", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    val graph = buildGraph(indicator, onProgress)

                    ApplicationManager.getApplication().invokeLater {
                        onComplete(graph)
                    }
                } catch (e: Exception) {
                    logger.error("Meta graph generation failed", e)
                    ApplicationManager.getApplication().invokeLater {
                        onError("메타 그래프 생성 실패: ${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 프로젝트 그래프를 동기적으로 생성합니다.
     * 반드시 백그라운드 스레드에서 호출해야 합니다.
     */
    fun buildGraph(
        indicator: ProgressIndicator? = null,
        onProgress: ((String) -> Unit)? = null
    ): ProjectGraph {
        val startTime = System.currentTimeMillis()

        // 실제 소스 루트가 있고 최상위 빈 루트가 아닌 서브 모듈들만 필터링
        val validModules = ModuleManager.getInstance(project).modules.filter { module ->
            val rootManager = ModuleRootManager.getInstance(module)
            rootManager.sourceRoots.isNotEmpty() && module.name != project.name
        }
        val isMultiModule = validModules.isNotEmpty()

        // Step 2: 멀티모듈 소스 루트 탐색 → PsiClassOwner 목록 수집
        indicator?.text = "프로젝트 파일 탐색 중..."
        onProgress?.invoke("프로젝트 파일을 탐색하고 있습니다...")

        val sourceFiles = ReadAction.compute<List<PsiClassOwner>, Throwable> {
            collectJavaAndKotlinFiles()
        }

        if (sourceFiles.isEmpty()) {
            onProgress?.invoke("분석 대상 소스 파일(Java/Kotlin)이 없습니다.")
            return emptyGraph()
        }

        val totalFiles = sourceFiles.size
        onProgress?.invoke("총 ${totalFiles}개의 소스 파일 발견. 분석을 시작합니다...")

        // Step 3: 파일별 ReadAction으로 PsiClass 분석 → FileNode 생성 및 CALLS 수집
        val nodes = mutableMapOf<String, FileNode>()
        val allCalls = mutableListOf<Relationship>()
        val projectBasePath = targetDirectory?.path ?: project.basePath ?: ""

        sourceFiles.forEachIndexed { index, psiFile ->
            indicator?.checkCanceled()
            indicator?.fraction = index.toDouble() / totalFiles
            indicator?.text = "분석 중: ${psiFile.name} (${index + 1}/$totalFiles)"

            try {
                val (fileNodes, fileCalls) = ReadAction.compute<Pair<List<Pair<String, FileNode>>, List<Relationship>>, Throwable> {
                    analyzeFile(psiFile, projectBasePath)
                }
                for ((path, node) in fileNodes) {
                    nodes[path] = node
                }
                allCalls.addAll(fileCalls)
            } catch (e: Exception) {
                logger.warn("Failed to analyze: ${psiFile.name}", e)
            }
        }

        onProgress?.invoke("${nodes.size}개 파일 분석 완료. 의존관계를 해석합니다...")

        // Step 4-5: 배치 인터페이스→구현체 해석 + resolvedImpl 채우기
        indicator?.text = "의존관계 해석 중..."
        indicator?.fraction = 0.8
        val resolvedNodes = dependencyResolver.resolveAll(nodes)

        // Step 6-7: 양방향 dependsOn/dependedBy 채우기 + Relationship 리스트 생성
        indicator?.text = "관계 그래프 구축 중..."
        indicator?.fraction = 0.9
        val relationships = ReadAction.compute<List<Relationship>, Throwable> {
            dependencyResolver.buildRelationships(resolvedNodes)
        }.toMutableList()

        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val currentFrameworkType = settings.state.frameworkType
        if (currentFrameworkType == FrameworkType.ANYFRAME) {
            val voRels = anyframeVoChainAnalyzer.analyze(resolvedNodes)
            relationships.addAll(voRels)
            for (rel in voRels) {
                resolvedNodes[rel.source]?.dependsOn?.add(rel.target)
                resolvedNodes[rel.target]?.dependedBy?.add(rel.source)
            }
        }
        
        // CALLS 관계 병합 및 노드의 dependsOn/dependedBy 업데이트
        relationships.addAll(allCalls)
        for (call in allCalls) {
            resolvedNodes[call.source]?.dependsOn?.add(call.target)
            resolvedNodes[call.target]?.dependedBy?.add(call.source)
        }

        // Phase 1c & Phase 3: RiskAssessment 계산
        val scoredNodes = resolvedNodes.mapValues { (path, node) ->
            val distinctDependsOn = node.dependsOn.distinct().toMutableList()
            val distinctDependedBy = node.dependedBy.distinct().toMutableList()

            var score = 0
            val reasons = mutableListOf<String>()

            // 1. Base Layer (기본 계층 위험도)
            val baseScore = when (node.fileType) {
                SpringFileType.CONTROLLER, SpringFileType.REST_CONTROLLER, SpringFileType.CONFIG, SpringFileType.FILTER, SpringFileType.INTERCEPTOR -> 3
                SpringFileType.REPOSITORY, SpringFileType.MAPPER, SpringFileType.SERVICE, SpringFileType.COMPONENT -> 2
                SpringFileType.UTIL, SpringFileType.DTO, SpringFileType.VO, SpringFileType.ENTITY, SpringFileType.VIEW, SpringFileType.UNKNOWN,
                SpringFileType.INTERFACE, SpringFileType.ABSTRACT_CLASS, SpringFileType.ENUM, SpringFileType.EXCEPTION_HANDLER, SpringFileType.EXCEPTION, SpringFileType.TEST -> 1
            }
            score += baseScore
            reasons.add("기본 계층 위험도 [${node.fileType}]: +$baseScore")

            // 2. Inbound Dependencies (피의존성)
            val inboundCount = distinctDependedBy.size
            if (inboundCount > 0) {
                score += inboundCount
                reasons.add("${inboundCount}개의 파일에서 이 파일을 의존/호출함: +$inboundCount")
            }

            // 3. Outbound API (엔드포인트 노출)
            val apiCount = node.apiEndpoints.size
            if (apiCount > 0) {
                score += apiCount
                reasons.add("${apiCount}개의 API 엔드포인트 노출: +$apiCount")
            }

            // 4. Complexity (복잡도: 외부 호출)
            val outboundCalls = allCalls.filter { it.source == path }.map { it.target }.toSet().size
            if (outboundCalls > 0) {
                val callScore = (outboundCalls * 0.5).toInt()
                if (callScore > 0) {
                    score += callScore
                    reasons.add("${outboundCalls}개의 외부 클래스 호출 (복잡도): +$callScore")
                }
            }

            // 5. ChangeRisk 분류
            val risk = when {
                score >= 8 -> ChangeRisk.CRITICAL
                score >= 5 -> ChangeRisk.HIGH
                score >= 3 -> ChangeRisk.MEDIUM
                else -> ChangeRisk.LOW
            }

            node.copy(
                dependsOn = distinctDependsOn,
                dependedBy = distinctDependedBy,
                riskAssessment = RiskAssessment(riskScore = score, changeRisk = risk, riskReasons = reasons)
            )
        }

        // Step 8: GraphStatistics 계산
        val statistics = calculateStatistics(scoredNodes, relationships)

        // Step 9: 그래프 조립
        indicator?.fraction = 1.0
        val elapsed = System.currentTimeMillis() - startTime
        onProgress?.invoke("메타 그래프 생성 완료 (${scoredNodes.size}개 파일, ${relationships.size}개 관계, ${elapsed}ms)")

        // 1. 단독 모듈 재분석 여부 판별
        val fileIndex = ProjectFileIndex.getInstance(project)
        val targetModule = targetDirectory?.let { fileIndex.getModuleForFile(it) }
        val isSingleModulePartialUpdate = targetModule != null && targetModule.name != project.name && isMultiModule

        val frameworkName = if (currentFrameworkType == FrameworkType.ANYFRAME) "anyframe" else "spring-boot"

        val graph = if (isSingleModulePartialUpdate) {
            // A. 단독 모듈 분석 모드: MULTI_LEVEL_2로 생성
            ProjectGraph(
                graphType = GraphType.MULTI_LEVEL_2,
                generatedAt = Instant.now().toString(),
                projectRoot = targetDirectory.path,
                framework = frameworkName,
                frameworkType = currentFrameworkType,
                files = scoredNodes,
                relationships = relationships,
                statistics = statistics
            )
        } else {
            // B. 프로젝트 전체 분석 모드 (SINGLE 또는 MULTI_LEVEL_1)
            val modulesList = if (isMultiModule) {
                collectModulesInfo(scoredNodes, validModules)
            } else {
                null
            }
            ProjectGraph(
                graphType = if (isMultiModule) GraphType.MULTI_LEVEL_1 else GraphType.SINGLE,
                generatedAt = Instant.now().toString(),
                projectRoot = projectBasePath,
                framework = frameworkName,
                frameworkType = currentFrameworkType,
                modules = modulesList,
                files = scoredNodes,
                relationships = relationships,
                statistics = statistics
            )
        }

        // JSON 파일 저장 및 부분 업데이트 수행
        val savedPath = if (graph.graphType == GraphType.MULTI_LEVEL_2 && targetModule != null) {
            val savedModulePath = exporter.exportToJson(graph, project)
            updateLevel1Metadata(targetModule, scoredNodes)
            savedModulePath
        } else {
            exporter.exportToJson(graph, project)
        }
        onProgress?.invoke("메타파일 저장 완료: $savedPath")

        return graph
    }

    // ── Step 2: 멀티모듈 소스 루트 탐색 ──────────

    /**
     * IntelliJ의 ProjectFileIndex를 활용하여
     * 프로젝트의 Java 및 Kotlin 소스 파일을 수집합니다.
     * 테스트 소스셋, 빌드 결과물(build/generated)은 제외됩니다.
     */
    private fun collectJavaAndKotlinFiles(): List<PsiClassOwner> {
        val result = mutableListOf<PsiClassOwner>()
        val psiManager = PsiManager.getInstance(project)
        val fileIndex = ProjectFileIndex.getInstance(project)

        val iterator = ContentIterator { vf ->
            if (!vf.isDirectory && (vf.extension == "java" || vf.extension == "kt")) {
                val path = vf.path.lowercase()
                val isTest = path.contains("/test/") || path.contains("/src/test/")
                val isBuild = path.contains("/build/") || path.contains("/out/") || path.contains("/target/") || 
                              path.contains("/.gradle/") || path.contains("/.idea/") || path.contains("/.metadata/") ||
                              path.contains("/.git/") || path.contains("/node_modules/")
                
                // 1. fileIndex가 소스 코드로 인정하거나
                // 2. targetDirectory가 명시된 경우, 빌드/테스트 경로가 아니면 강제 수집 (Source Root 매핑이 안 된 경우 대비)
                val isSource = (fileIndex.isInSourceContent(vf) && !fileIndex.isInTestSourceContent(vf)) ||
                               (targetDirectory != null && !isTest && !isBuild)
                               
                if (isSource) {
                    val psiFile = psiManager.findFile(vf) as? PsiClassOwner
                    if (psiFile != null) {
                        result.add(psiFile)
                    }
                }
            }
            true
        }

        if (targetDirectory != null) {
            com.intellij.openapi.vfs.VfsUtilCore.iterateChildrenRecursively(targetDirectory, null) { vf ->
                iterator.processFile(vf)
            }
        } else {
            fileIndex.iterateContent(iterator)
        }

        logger.info("Collected ${result.size} source files from project")
        return result
    }

    // ── Step 3: 파일별 분석 ──────────────────────

    /**
     * 단일 PsiClassOwner(Java 또는 Kotlin 파일)에서 public 클래스를 기준으로 FileNode를 생성합니다.
     * 한 파일에 여러 클래스가 있는 경우, public 클래스를 우선합니다.
     */
    private fun analyzeFile(psiFile: PsiClassOwner, projectBasePath: String): Pair<List<Pair<String, FileNode>>, List<Relationship>> {
        val relativePath = psiFile.virtualFile?.path
            ?.removePrefix(projectBasePath)
            ?.removePrefix("/")
            ?: return Pair(emptyList(), emptyList())

        val classes = psiFile.classes
        if (classes.isEmpty()) return Pair(emptyList(), emptyList())

        // public 클래스 우선, 없으면 첫 번째 클래스 사용
        val primaryClass = classes.firstOrNull { it.hasModifierProperty(PsiModifier.PUBLIC) }
            ?: classes.first()

        var node = annotationResolver.resolve(primaryClass, relativePath)
        
        // Phase 1c: 보강 분석기 연동
        if (node.fileType == SpringFileType.REST_CONTROLLER || node.fileType == SpringFileType.CONTROLLER) {
            val endpoints = endpointAnalyzer.analyze(primaryClass)
            node = node.copy(apiEndpoints = endpoints)
        } else if (node.fileType == SpringFileType.CONFIG) {
            val beans = beanAnalyzer.analyze(primaryClass)
            node = node.copy(beanDefinitions = beans)
        } else if (node.fileType == SpringFileType.ENTITY) {
            val relations = entityAnalyzer.analyze(primaryClass)
            node = node.copy(entityRelations = relations)
        }
        
        val calls = callRelationAnalyzer.analyze(primaryClass, projectBasePath).toMutableList()
        
        val settings = net.ib.ixpert.ops.wuwagent.setting.SettingsState.getInstance()
        val isAnyframe = settings.state.frameworkType == FrameworkType.ANYFRAME

        if (isAnyframe) {
            if (node.anyframeRole == AnyframeRole.SVC) {
                val endpoints = anyframeServiceIdAnalyzer.analyze(primaryClass)
                node = node.copy(serviceEndpoints = endpoints)
            } else if (node.anyframeRole == AnyframeRole.DEM || node.anyframeRole == AnyframeRole.DQM) {
                val demMethods = anyframeDemAnalyzer.analyze(primaryClass)
                node = node.copy(demMethods = demMethods)
            }
            val anyframeDeps = anyframeDependencyAnalyzer.analyze(primaryClass, projectBasePath)
            val anyframeBizCalls = anyframeBizCallAnalyzer.analyze(primaryClass, projectBasePath)
            calls.addAll(anyframeDeps)
            calls.addAll(anyframeBizCalls)
        }
        
        // Adaptive File Discovery 지원: 한글 주석 + 메서드명 수집
        val koreanComments = extractKoreanComments(primaryClass)
        val methodNames = primaryClass.methods.map { it.name }.filter { it != primaryClass.name }
        node = node.copy(
            koreanComments = koreanComments,
            methodNames = methodNames
        )
        
        return Pair(listOf(relativePath to node), calls)
    }
    // ── 한글 주석 추출 (Adaptive File Discovery) ───────

    private val koreanPattern = Regex("[가-힣]{2,}")

    /**
     * PsiClass에서 한글이 포함된 주석을 추출합니다.
     * KDoc, JavaDoc, 라인 주석(//) 및 블록 주석(/* */)을 모두 검사합니다.
     */
    private fun extractKoreanComments(psiClass: com.intellij.psi.PsiClass): List<String> {
        val comments = mutableListOf<String>()

        // 1. 클래스 레벨 KDoc/JavaDoc
        psiClass.docComment?.let { doc ->
            val text = doc.text
            if (koreanPattern.containsMatchIn(text)) {
                // 태그와 포맷 문자 제거 후 한글 포함 라인만 추출
                text.lines()
                    .map { it.replace(Regex("[/*@]"), "").trim() }
                    .filter { it.isNotBlank() && koreanPattern.containsMatchIn(it) }
                    .forEach { comments.add(it) }
            }
        }

        // 2. 메서드 레벨 KDoc/JavaDoc
        for (method in psiClass.methods) {
            method.docComment?.let { doc ->
                val text = doc.text
                if (koreanPattern.containsMatchIn(text)) {
                    text.lines()
                        .map { it.replace(Regex("[/*@]"), "").trim() }
                        .filter { it.isNotBlank() && koreanPattern.containsMatchIn(it) }
                        .take(2) // 메서드당 최대 2줄
                        .forEach { comments.add(it) }
                }
            }
        }

        return comments.distinct().take(10) // 클래스당 최대 10개 주석
    }

    // ── Step 8: 통계 계산 ────────────────────────

    private fun calculateStatistics(nodes: Map<String, FileNode>, relationships: List<Relationship>): GraphStatistics {
        return GraphStatistics(
            totalFiles = nodes.size,
            controllers = nodes.values.count { it.fileType in listOf(SpringFileType.REST_CONTROLLER, SpringFileType.CONTROLLER) },
            services = nodes.values.count { it.fileType == SpringFileType.SERVICE },
            repositories = nodes.values.count { it.fileType in listOf(SpringFileType.REPOSITORY, SpringFileType.MAPPER) },
            entities = nodes.values.count { it.fileType == SpringFileType.ENTITY },
            configs = nodes.values.count { it.fileType == SpringFileType.CONFIG },
            dtos = nodes.values.count { it.fileType in listOf(SpringFileType.DTO, SpringFileType.VO) },
            utils = nodes.values.count { it.fileType == SpringFileType.UTIL },
            views = nodes.values.count { it.fileType == SpringFileType.VIEW },
            components = nodes.values.count { it.fileType in listOf(SpringFileType.COMPONENT, SpringFileType.FILTER, SpringFileType.INTERCEPTOR) },
            others = nodes.values.count { it.fileType !in listOf(
                SpringFileType.REST_CONTROLLER, SpringFileType.CONTROLLER,
                SpringFileType.SERVICE, SpringFileType.REPOSITORY, SpringFileType.MAPPER,
                SpringFileType.ENTITY, SpringFileType.CONFIG,
                SpringFileType.DTO, SpringFileType.VO,
                SpringFileType.UTIL, SpringFileType.VIEW,
                SpringFileType.COMPONENT, SpringFileType.FILTER, SpringFileType.INTERCEPTOR
            ) },
            totalRelationships = relationships.size
        )
    }

    private fun emptyGraph(): ProjectGraph {
        return ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = targetDirectory?.path ?: project.basePath ?: "",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
    }

    private fun collectModulesInfo(
        nodes: Map<String, FileNode>,
        validModules: List<com.intellij.openapi.module.Module>
    ): List<ModuleInfo> {
        val baseDir = project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        } ?: return emptyList()

        return validModules.map { module ->
            val rootManager = ModuleRootManager.getInstance(module)
            val contentRoots = rootManager.contentRoots
            val moduleRoot = contentRoots.firstOrNull()

            // 프로젝트 루트 기준 모듈의 상대 경로
            val rootPath = moduleRoot?.let {
                VfsUtilCore.getRelativePath(it, baseDir)
            } ?: ""

            // 메타파일 상대 경로
            val metadataPath = if (rootPath.isEmpty()) {
                ".meta/project-graph.json"
            } else {
                "$rootPath/.meta/project-graph.json"
            }

            // 해당 모듈 디렉터리 내에 포함된 파일만 매핑
            val moduleFiles = nodes.filter { (path, _) ->
                path.startsWith("$rootPath/") || (rootPath.isEmpty() && !path.contains("/"))
            }

            // 모듈의 public API 요약 (REST Endpoints)
            val publicApis = moduleFiles.values.flatMap { node ->
                node.apiEndpoints.map { "${it.httpMethod} ${it.path}" }
            }.distinct()

            ModuleInfo(
                name = module.name,
                rootPath = rootPath,
                metadataPath = metadataPath,
                dependsOnModules = emptyList(), // Phase 1a 기본값
                lastIndexedAt = System.currentTimeMillis(),
                fileCount = moduleFiles.size,
                publicApis = if (publicApis.isNotEmpty()) publicApis else null
            )
        }
    }

    /**
     * 특정 모듈 단독 재분석 시, 기존 전역 Level 1 메타데이터의 해당 모듈 요약 정보를 부분 업데이트(Partial Update)합니다.
     */
    private fun updateLevel1Metadata(
        targetModule: com.intellij.openapi.module.Module,
        moduleNodes: Map<String, FileNode>
    ) {
        try {
            val basePath = project.basePath ?: return
            val rootMetaFile = File(basePath, ".meta/project-graph.json")
            if (!rootMetaFile.exists()) return

            val jsonContent = rootMetaFile.readText(Charsets.UTF_8)
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
            val rootGraph = gson.fromJson(jsonContent, ProjectGraph::class.java)

            if (rootGraph.graphType == GraphType.MULTI_LEVEL_1 && rootGraph.modules != null) {
                val rootManager = ModuleRootManager.getInstance(targetModule)
                val contentRoots = rootManager.contentRoots
                val moduleRoot = contentRoots.firstOrNull()
                val baseDir = LocalFileSystem.getInstance().findFileByPath(basePath) ?: return
                val rootPath = moduleRoot?.let { VfsUtilCore.getRelativePath(it, baseDir) } ?: ""

                // 해당 모듈의 public API 요약 (REST Endpoints)
                val publicApis = moduleNodes.values.flatMap { node ->
                    node.apiEndpoints.map { "${it.httpMethod} ${it.path}" }
                }.distinct()

                // 기존 modules 리스트에서 이 모듈 항목을 찾아 갱신
                val updatedModules = rootGraph.modules.map { moduleInfo ->
                    if (moduleInfo.name == targetModule.name) {
                        moduleInfo.copy(
                            lastIndexedAt = System.currentTimeMillis(),
                            fileCount = moduleNodes.size,
                            publicApis = if (publicApis.isNotEmpty()) publicApis else null
                        )
                    } else {
                        moduleInfo
                    }
                }

                // Level 1 파일 재기록
                val updatedRootGraph = rootGraph.copy(
                    generatedAt = Instant.now().toString(),
                    modules = updatedModules
                )
                rootMetaFile.writeText(gson.toJson(updatedRootGraph), Charsets.UTF_8)
                logger.info("Partially updated Level 1 metadata for module: ${targetModule.name}")
            }
        } catch (e: Exception) {
            logger.warn("Failed to partially update Level 1 metadata: ${e.message}")
        }
    }
}
