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
import com.intellij.openapi.roots.ModuleRootManager
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

        // Step 3: 파일별 ReadAction으로 PsiClass 분석 → FileNode 생성
        val nodes = mutableMapOf<String, FileNode>()
        val projectBasePath = project.basePath ?: ""

        sourceFiles.forEachIndexed { index, psiFile ->
            indicator?.checkCanceled()
            indicator?.fraction = index.toDouble() / totalFiles
            indicator?.text = "분석 중: ${psiFile.name} (${index + 1}/$totalFiles)"

            try {
                val fileNodes = ReadAction.compute<List<Pair<String, FileNode>>, Throwable> {
                    analyzeFile(psiFile, projectBasePath)
                }
                for ((path, node) in fileNodes) {
                    nodes[path] = node
                }
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
        }

        // Phase 1c: ChangeRisk 계산
        val scoredNodes = resolvedNodes.mapValues { (_, node) ->
            val score = node.dependedBy.size
            val risk = when {
                score >= CRITICAL_THRESHOLD -> ChangeRisk.CRITICAL
                score >= HIGH_THRESHOLD -> ChangeRisk.HIGH
                score >= MEDIUM_THRESHOLD -> ChangeRisk.MEDIUM
                else -> ChangeRisk.LOW
            }
            node.copy(riskScore = score, changeRisk = risk)
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
     * IntelliJ의 ModuleManager + ModuleRootManager를 활용하여
     * 프로젝트의 모든 모듈에서 Java 및 Kotlin 소스 파일을 수집합니다.
     * 테스트 소스셋, 빌드 결과물(build/generated)은 제외됩니다.
     */
    private fun collectJavaAndKotlinFiles(): List<PsiClassOwner> {
        val result = mutableListOf<PsiClassOwner>()
        val psiManager = PsiManager.getInstance(project)

        for (module in ModuleManager.getInstance(project).modules) {
            val sourceRoots = ModuleRootManager.getInstance(module)
                .getSourceRoots(JavaSourceRootType.SOURCE)

            for (root in sourceRoots) {
                VfsUtilCore.iterateChildrenRecursively(root, null) { vf ->
                    if (!vf.isDirectory && (vf.extension == "java" || vf.extension == "kt")) {
                        val psiFile = psiManager.findFile(vf) as? PsiClassOwner
                        if (psiFile != null) {
                            result.add(psiFile)
                        }
                    }
                    true
                }
            }
        }

        logger.info("Collected ${result.size} source files from ${ModuleManager.getInstance(project).modules.size} modules")
        return result
    }

    // ── Step 3: 파일별 분석 ──────────────────────

    /**
     * 단일 PsiClassOwner(Java 또는 Kotlin 파일)에서 public 클래스를 기준으로 FileNode를 생성합니다.
     * 한 파일에 여러 클래스가 있는 경우, public 클래스를 우선합니다.
     */
    private fun analyzeFile(psiFile: PsiClassOwner, projectBasePath: String): List<Pair<String, FileNode>> {
        val relativePath = psiFile.virtualFile?.path
            ?.removePrefix(projectBasePath)
            ?.removePrefix("/")
            ?: return emptyList()

        val classes = psiFile.classes
        if (classes.isEmpty()) return emptyList()

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
        
        return listOf(relativePath to node)
    }

    // ── Step 8: 통계 계산 ────────────────────────

    private fun calculateStatistics(nodes: Map<String, FileNode>, relationships: List<Relationship>): GraphStatistics {
        return GraphStatistics(
            totalFiles = nodes.size,
            controllers = nodes.values.count { it.fileType in listOf(SpringFileType.REST_CONTROLLER, SpringFileType.CONTROLLER) },
            services = nodes.values.count { it.fileType == SpringFileType.SERVICE },
            repositories = nodes.values.count { it.fileType == SpringFileType.REPOSITORY },
            entities = nodes.values.count { it.fileType == SpringFileType.ENTITY },
            configs = nodes.values.count { it.fileType == SpringFileType.CONFIG },
            others = nodes.values.count { it.fileType !in listOf(
                SpringFileType.REST_CONTROLLER, SpringFileType.CONTROLLER,
                SpringFileType.SERVICE, SpringFileType.REPOSITORY,
                SpringFileType.ENTITY, SpringFileType.CONFIG
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
