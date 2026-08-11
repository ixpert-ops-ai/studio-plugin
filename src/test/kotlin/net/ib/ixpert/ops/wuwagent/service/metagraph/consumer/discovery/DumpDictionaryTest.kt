package net.ib.ixpert.ops.wuwagent.service.metagraph.consumer.discovery

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import net.ib.ixpert.ops.wuwagent.service.metagraph.model.ProjectGraph
import org.junit.Test
import java.io.File

class DumpDictionaryTest {
    @Test
    fun dump() {
        val mapper = jacksonObjectMapper()
        val jsonFile = File("C:/Workspace/graph/project-graph-i/project-graph.json")
        val graph: ProjectGraph = mapper.readValue(jsonFile)
        
        val dict = DomainDictionary.load(graph)
        
        val text = "마이페이지의 1:1 문의 내역이나 상품 Q&A 내역에서, 고객이 작성한 문의글 중 '주문'과 연동된 문의인 경우, 목록이나 상세 화면에서 해당 주문의 현재 배송 상태(결제완료, 배송중, 배송완료 등)가 함께 표시되도록 기능 개선."
        
        val stopWords = setOf(
            "controller", "service", "repository", "entity", "dto", "vo", "request", "response", 
            "mapper", "view", "page", "screen", "api", "impl", "config", "exception", "handler", 
            "util", "action", "svc", "svo", "dvo", "dao", "bo",
            "화면", "컨트롤러", "서비스", "레파지토리", "저장소", "엔티티", "디티오", "매퍼", 
            "액션", "페이지", "에이피아이", "구현체", "인터페이스"
        )
        
        val words = text.split(Regex("\\s+"))
        val nouns = mutableListOf<String>()
        for (word in words) {
            val cleanWord = word.replace(Regex("[^가-힣a-zA-Z0-9]"), "")
            if (cleanWord.length < 2) continue
            if (stopWords.contains(cleanWord.lowercase())) continue
            
            if (cleanWord.endsWith("한다") || cleanWord.endsWith("해라") || cleanWord.endsWith("추가") || cleanWord.endsWith("수정") || cleanWord.endsWith("삭제")) {
                // skip
            } else {
                nouns.add(cleanWord.replace("을", "").replace("를", "").replace("이", "").replace("가", "").replace("은", "").replace("는", ""))
            }
        }
        
        val outFile = File("C:/Users/dffrp/.gemini/antigravity/brain/dead1e82-1168-4141-b615-c0196d0a2637/scratch/dict-out.txt")
        outFile.writeText("=== Dictionary Mappings for TC8 Nouns ===\n")
        for (noun in nouns) {
            val trans = dict.translate(noun)
            outFile.appendText(noun + " -> " + trans.joinToString(",") + "\n")
        }
    }
}
