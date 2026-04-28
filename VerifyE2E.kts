import net.ib.ixpert.ops.wuwagent.agent.RequirementAnalysisPipeline
import net.ib.ixpert.ops.wuwagent.client.OllamaClient

fun main() {
    val raw = """
요구사항 요약
설문 결과를 Excel 파일로 다운로드할 수 있는 기능을 추가해야 합니다. 이 기능은 Survey 관련 기능과 연동되어야 하며, Controller, Service, DAO 계층에서 필요한 수정이나 추가가 필요합니다.

수정 대상 파일

| 순서 | 파일 경로 | 유형 | 작업 내용 |
|---|---|---|---|
| 1 | src/main/java/net/infobank/iss/survey/dao/SurveyDaoImpl.java | 수정 | 설문 결과 데이터를 조회하는 메서드 추가 또는 수정 |
| 2 | src/main/java/net/infobank/iss/survey/service/SurveyServiceImpl.java | 수정 | 설문 결과를 Excel로 변환하는 로직 추가 |

작업 시 주의사항
SurveyDaoImpl과 SurveyServiceImpl은 데이터베이스 연동 및 로직 변경으로 인해 ChangeRisk가 HIGH입니다. ??
"""
    
    val pipeline = RequirementAnalysisPipeline(OllamaClient())
    val result = pipeline.parseResponse(raw)
    println("Summary: " + result.summary)
    println("Target files size: " + result.targetFiles.size)
    result.targetFiles.forEach { println(it) }
}
