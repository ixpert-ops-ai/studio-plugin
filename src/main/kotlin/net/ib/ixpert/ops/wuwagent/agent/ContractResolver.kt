package net.ib.ixpert.ops.wuwagent.agent

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import net.ib.ixpert.ops.wuwagent.client.LLMClient
import net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.GraphLoader
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.*

// ═══════════════════════════════════════════════════════════════
// Data Models
// ═══════════════════════════════════════════════════════════════

/**
 * 단일 메서드의 시그니처 계약.
 * 모든 관련 파일이 이 계약을 준수해야 합니다.
 */
data class MethodContract(
    val methodName: String,
    val returnType: String,
    val paramSignature: String,
    val isStatic: Boolean,
    val sourceFile: String
)

/**
 * 파일별 역할과 준수해야 할 메서드 계약 목록.
 */
data class FileContract(
    val filePath: String,
    val role: FileRole,
    val methods: List<MethodContract>
)

enum class FileRole {
    DATA_STRUCTURE,
    INTERFACE_DECLARATION,
    OVERRIDE_IMPLEMENTATION,
    CALLER,
    UTILITY
}

/**
 * 전체 구현 작업의 시그니처 계약.
 */
data class ImplementationContract(
    val fileContracts: List<FileContract>,
    val sharedMethods: List<MethodContract>
)

// ═══════════════════════════════════════════════════════════════
// ContractResolver
// ═══════════════════════════════════════════════════════════════

/**
 * [Phase 1] Contract-First 시그니처 확정기.
 *
 * RequirementAnalysisResult의 targetFiles와 MetaGraph를 조합하여,
 * 모든 파일이 공유해야 할 메서드 시그니처 계약(Contract)을 확정합니다.
 *
 * 확정 전략:
 * 1. PSI 기반: 기존 메서드 수정인 경우, Interface/Impl의 시그니처를 PSI에서 추출
 * 2. LLM 보완: 신규 메서드인 경우, 프로젝트 컨벤션을 분석하여 짧은 LLM 호출로 시그니처 결정
 */
class ContractResolver(
    private val project: Project,
    private val client: LLMClient
) {
    private val logger = Logger.getInstance(ContractResolver::class.java)

    /**
     * 동일 세션 내 반복 resolve 시 중복 PSI 탐색을 방지하는 시그니처 캐시.
     * key: 메서드명, value: 확정된 MethodContract
     */
    private val signatureCache = java.util.concurrent.ConcurrentHashMap<String, MethodContract>()

    /**
     * 요구사항 분석 결과를 기반으로 시그니처 계약을 생성합니다.
     *
     * @param analysisResult Phase 2a의 결과 (targetFiles + summary)
     * @return ImplementationContract 또는 null (분석 불가 시)
     */
    fun resolve(analysisResult: RequirementAnalysisResult): ImplementationContract? {
        val graphLoader = project.getService(GraphLoader::class.java)
        val graph = graphLoader?.loadGraph()

        if (graph == null) {
            logger.warn("ContractResolver: MetaGraph가 없어 Contract 생성을 건너뜁니다.")
            return null
        }

        val targets = analysisResult.targetFiles
        if (targets.isEmpty()) return null

        logger.info("ContractResolver: ${targets.size}개 파일에 대한 Contract 생성 시작")

        // Step 1: 각 파일의 역할(Role) 결정
        val fileRoles = targets.associate { it.path to determineRole(it.path, graph) }
        logger.info("ContractResolver: 파일 역할 결정 완료 → $fileRoles")

        // Step 2: Interface-Impl 체인에서 기존 메서드 시그니처 추출 (PSI 기반)
        val existingSignatures = ReadAction.compute<List<MethodContract>, Throwable> {
            extractExistingSignatures(targets, graph)
        }

        // Step 3: 신규 메서드의 시그니처를 LLM으로 확정
        val newMethodSignatures = resolveNewMethodSignatures(
            analysisResult, existingSignatures, graph
        )

        // Step 4: 공유 메서드 목록 확정
        val sharedMethods = (existingSignatures + newMethodSignatures).distinctBy { it.methodName }

        // 캐시 업데이트: 확정된 시그니처를 캐시에 저장
        sharedMethods.forEach { signatureCache[it.methodName] = it }

        if (sharedMethods.isEmpty()) {
            logger.info("ContractResolver: 공유 메서드가 없어 Contract를 생략합니다.")
            return null
        }

        // Step 5: FileContract 조립
        val fileContracts = targets.map { target ->
            val role = fileRoles[target.path] ?: FileRole.CALLER
            FileContract(
                filePath = target.path,
                role = role,
                methods = sharedMethods
            )
        }

        logger.info("ContractResolver: Contract 생성 완료 — 공유 메서드 ${sharedMethods.size}개, 파일 ${fileContracts.size}개")
        return ImplementationContract(fileContracts, sharedMethods)
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 1: 파일 역할 결정
    // ═══════════════════════════════════════════════════════════════

    private fun determineRole(path: String, graph: ProjectGraph): FileRole {
        val lowerPath = path.lowercase().replace("\\", "/")
        val normalizedPath = lowerPath.removePrefix("/")
        val fileNode = graph.files.values.find {
            it.path.replace("\\", "/").lowercase().endsWith(normalizedPath)
        }

        return when {
            // DTO/Entity/VO
            fileNode?.fileType in listOf(SpringFileType.DTO, SpringFileType.VO, SpringFileType.ENTITY) ->
                FileRole.DATA_STRUCTURE
            lowerPath.let { it.contains("dto") || it.contains("entity") || it.contains("vo") }
                && !lowerPath.contains("controller") ->
                FileRole.DATA_STRUCTURE

            // Interface
            fileNode?.isInterface == true -> FileRole.INTERFACE_DECLARATION
            !lowerPath.contains("impl") && lowerPath.let {
                it.endsWith("dao.java") || it.endsWith("service.java") ||
                it.endsWith("repository.java") || it.endsWith("mapper.java")
            } -> FileRole.INTERFACE_DECLARATION

            // Impl
            lowerPath.contains("impl") -> FileRole.OVERRIDE_IMPLEMENTATION

            // Utility
            fileNode?.fileType == SpringFileType.UTIL -> FileRole.UTILITY
            lowerPath.let { it.contains("util") || it.contains("helper") || it.contains("exporter") } ->
                FileRole.UTILITY

            // Controller/Caller
            fileNode?.fileType in listOf(SpringFileType.CONTROLLER, SpringFileType.REST_CONTROLLER) ->
                FileRole.CALLER
            lowerPath.contains("controller") -> FileRole.CALLER

            else -> FileRole.CALLER
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 2: PSI 기반 기존 시그니처 추출
    // ═══════════════════════════════════════════════════════════════

    /**
     * Interface/Impl 파일에서 요구사항과 관련된 기존 메서드 시그니처를 추출합니다.
     * description에 언급된 메서드명이 실제 소스에 존재하면 그 시그니처를 계약으로 확정합니다.
     */
    private fun extractExistingSignatures(
        targets: List<TargetFileSpec>,
        graph: ProjectGraph
    ): List<MethodContract> {
        val contracts = mutableListOf<MethodContract>()
        val processedMethods = mutableSetOf<String>()

        // Interface 파일들을 우선 처리 (시그니처의 근거)
        val interfaceTargets = targets.filter { target ->
            val lowerPath = target.path.lowercase()
            !lowerPath.contains("impl") && lowerPath.let {
                it.endsWith("dao.java") || it.endsWith("service.java") ||
                it.endsWith("repository.java") || it.endsWith("mapper.java")
            }
        }

        // 이후 Impl 파일들 처리
        val allTargets = interfaceTargets + (targets - interfaceTargets.toSet())

        for (target in allTargets) {
            val absolutePath = "${project.basePath}/${target.path}".replace("//", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: continue
            val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue
            val psiClass = psiFile.classes.firstOrNull() ?: continue

            // description에서 메서드명 후보 추출
            val methodHints = extractMethodHints(target.description)

            for (method in psiClass.methods) {
                if (method.name in processedMethods) continue

                // 캐시에 이미 있으면 캐시된 계약 사용 (중복 PSI 탐색 방지)
                val cached = signatureCache[method.name]
                if (cached != null) {
                    contracts.add(cached)
                    processedMethods.add(method.name)
                    continue
                }

                if (methodHints.isEmpty() || methodHints.any { hint ->
                    method.name.contains(hint, ignoreCase = true) || hint.contains(method.name, ignoreCase = true)
                }) {
                    val returnType = method.returnType?.presentableText ?: "void"
                    val params = method.parameterList.parameters.joinToString(", ") {
                        "${it.type.presentableText} ${it.name}"
                    }
                    val isStatic = method.hasModifierProperty("static")

                    val contract = MethodContract(
                        methodName = method.name,
                        returnType = returnType,
                        paramSignature = params,
                        isStatic = isStatic,
                        sourceFile = target.path
                    )
                    contracts.add(contract)
                    processedMethods.add(method.name)
                    signatureCache[method.name] = contract  // 캐시에 저장
                    logger.info("ContractResolver: PSI 시그니처 추출 → ${method.name}($params): $returnType [${target.path}]")
                }
            }
        }

        return contracts
    }

    /**
     * description 텍스트에서 메서드명 후보를 추출합니다.
     * camelCase 패턴, 영어 동사+명사 패턴을 인식합니다.
     */
    private fun extractMethodHints(description: String): Set<String> {
        val hints = mutableSetOf<String>()

        // camelCase 메서드명 패턴 (selectSurveyResult, downloadCsv 등)
        val camelPattern = Regex("""([a-z][a-zA-Z0-9]*(?:List|Result|Data|Info|Csv|Excel|Download|Export|Select|Insert|Update|Delete|Find|Get|Create))""")
        camelPattern.findAll(description).forEach { hints.add(it.groupValues[1]) }

        // 동사+명사 패턴
        val verbPattern = Regex("""(select|insert|update|delete|find|get|create|download|export)\w*""", RegexOption.IGNORE_CASE)
        verbPattern.findAll(description).forEach { hints.add(it.value) }

        return hints
    }

    // ═══════════════════════════════════════════════════════════════
    // Step 3: LLM 기반 신규 메서드 시그니처 확정
    // ═══════════════════════════════════════════════════════════════

    /**
     * 기존 메서드로 매칭되지 않은 신규 메서드의 시그니처를 LLM으로 확정합니다.
     * 프로젝트의 기존 컨벤션(반환 타입 패턴)을 LLM에 제공하여 일관된 결정을 유도합니다.
     */
    private fun resolveNewMethodSignatures(
        analysisResult: RequirementAnalysisResult,
        existingContracts: List<MethodContract>,
        graph: ProjectGraph
    ): List<MethodContract> {
        // 이미 PSI에서 추출된 메서드명
        val existingMethodNames = existingContracts.map { it.methodName }.toSet()

        // 신규 메서드가 필요한 파일들 식별
        val filesNeedingNewMethods = analysisResult.targetFiles.filter { target ->
            val lowerPath = target.path.lowercase()
            val desc = target.description.lowercase()
            // "신규" 태그이거나, description에 "추가"/"생성" 키워드가 있으면서 기존 메서드와 매칭 안 된 경우
            (target.type.contains("신규") || desc.contains("추가") || desc.contains("생성") || desc.contains("new")) &&
            (lowerPath.contains("dao") || lowerPath.contains("service") ||
             lowerPath.contains("controller") || lowerPath.contains("util"))
        }

        if (filesNeedingNewMethods.isEmpty()) return emptyList()

        // 프로젝트 컨벤션 수집: 기존 메서드들의 반환 타입 패턴
        val conventionContext = buildConventionContext(graph)
        if (conventionContext.isBlank()) return emptyList()

        // 짧은 LLM 호출로 시그니처 결정
        val methodDescriptions = filesNeedingNewMethods.joinToString("\n") { target ->
            "- [${target.path}] ${target.description}"
        }

        val systemPrompt = """
            당신은 Spring Boot 프로젝트의 메서드 시그니처를 결정하는 아키텍트입니다.
            프로젝트의 기존 메서드 컨벤션을 분석하여, 새로 추가할 메서드의 시그니처를 결정하세요.
            
            ## 규칙
            1. 반환 타입은 기존 컨벤션에서 가장 유사한 패턴을 따르세요.
            2. 파라미터 타입도 기존 패턴과 일치시키세요.
            3. 메서드명은 기존 네이밍 규칙을 따르세요.
            4. 하나의 메서드에 대해 모든 계층(DAO, Service, Controller)이 동일한 시그니처를 사용해야 합니다.
            
            ## 응답 포맷 (반드시 이 형식만 사용)
            각 메서드를 한 줄씩, 아래 형식으로 출력하세요. 설명이나 주석은 금지합니다.
            METHOD|메서드명|반환타입|파라미터타입1 파라미터명1, 파라미터타입2 파라미터명2|static여부(true/false)
        """.trimIndent()

        val userPrompt = """
            ## 기존 프로젝트 메서드 컨벤션
            $conventionContext
            
            ## 새로 추가할 기능 설명
            $methodDescriptions
            
            ## 요구사항 요약
            ${analysisResult.summary}
        """.trimIndent()

        return try {
            logger.info("ContractResolver: LLM 시그니처 확정 호출 시작")
            val response = client.chat(systemPrompt, userPrompt, null)
            val responseText = response?.message?.content ?: ""
            parseMethodContracts(responseText)
        } catch (e: Exception) {
            // LLM 호출 실패 시 PSI 추출 결과만으로 partial contract 생성
            // (네트워크 오류, 토큰 초과 등)
            logger.warn("ContractResolver: LLM 시그니처 확정 실패 — PSI 기반 partial contract로 진행: ${e.message}")
            emptyList()
        }
    }

    /**
     * MetaGraph에서 기존 메서드들의 반환 타입 패턴을 수집하여 컨벤션 컨텍스트를 구성합니다.
     * Interface 파일의 메서드 시그니처를 PSI에서 추출합니다.
     */
    private fun buildConventionContext(graph: ProjectGraph): String {
        val conventions = mutableListOf<String>()

        // Interface 파일들에서 기존 메서드 시그니처 샘플링 (최대 10개)
        val interfaceFiles = graph.files.entries
            .filter { it.value.isInterface && it.value.fileType in listOf(SpringFileType.SERVICE, SpringFileType.REPOSITORY, SpringFileType.MAPPER) }
            .take(5)

        for ((path, node) in interfaceFiles) {
            val absolutePath = "${project.basePath}/$path".replace("//", "/")
            val virtualFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: continue

            try {
                val methods = ReadAction.compute<List<String>, Throwable> {
                    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: return@compute emptyList()
                    val psiClass = psiFile.classes.firstOrNull() ?: return@compute emptyList()

                    psiClass.methods.take(5).map { method ->
                        val returnType = method.returnType?.presentableText ?: "void"
                        val params = method.parameterList.parameters.joinToString(", ") {
                            "${it.type.presentableText} ${it.name}"
                        }
                        "- ${node.className}.${method.name}($params) → $returnType"
                    }
                }
                conventions.addAll(methods)
            } catch (e: Exception) {
                logger.debug("ContractResolver: 컨벤션 추출 실패 ($path): ${e.message}")
            }
        }

        return if (conventions.isEmpty()) "" else conventions.joinToString("\n")
    }

    /**
     * LLM 응답에서 METHOD| 형식의 시그니처를 파싱합니다.
     */
    private fun parseMethodContracts(response: String): List<MethodContract> {
        return response.lines()
            .filter { it.trimStart().startsWith("METHOD|") }
            .mapNotNull { line ->
                val parts = line.trimStart().removePrefix("METHOD|").split("|")
                if (parts.size >= 4) {
                    MethodContract(
                        methodName = parts[0].trim(),
                        returnType = parts[1].trim(),
                        paramSignature = parts[2].trim(),
                        isStatic = parts[3].trim().equals("true", ignoreCase = true),
                        sourceFile = "LLM-resolved"
                    )
                } else {
                    logger.warn("ContractResolver: 파싱 불가 라인 → $line")
                    null
                }
            }.also {
                logger.info("ContractResolver: LLM에서 ${it.size}개 시그니처 파싱 완료")
            }
    }
}
