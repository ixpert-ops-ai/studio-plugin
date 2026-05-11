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
class ProjectGraphBuilder(private val project: Project) {

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
        val projectBasePath = project.basePath ?: ""

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

        val graph = ProjectGraph(
            generatedAt = Instant.now().toString(),
            projectRoot = projectBasePath,
            files = scoredNodes,
            relationships = relationships,
            statistics = statistics
        )

        // JSON 파일 저장
        val savedPath = exporter.exportToJson(graph, project)
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

        fileIndex.iterateContent { vf ->
            if (!vf.isDirectory && (vf.extension == "java" || vf.extension == "kt")) {
                // 소스 디렉토리 내부에 있으면서 테스트 코드가 아닌 파일만 수집
                if (fileIndex.isInSourceContent(vf) && !fileIndex.isInTestSourceContent(vf)) {
                    val psiFile = psiManager.findFile(vf) as? PsiClassOwner
                    if (psiFile != null) {
                        result.add(psiFile)
                    }
                }
            }
            true
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
        
        val calls = callRelationAnalyzer.analyze(primaryClass, projectBasePath)
        
        return Pair(listOf(relativePath to node), calls)
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
            projectRoot = project.basePath ?: "",
            files = emptyMap(),
            relationships = emptyList(),
            statistics = GraphStatistics()
        )
    }
}
