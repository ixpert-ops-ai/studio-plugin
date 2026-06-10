package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ImplementContextBuilderTest {

    @Test
    fun testDetermineFileType() {
        assertEquals(ImplementFileType.JAVA_LIKE, ImplementContextBuilder.determineFileType("src/main/java/com/example/MyService.java", ""))
        assertEquals(ImplementFileType.JAVA_LIKE, ImplementContextBuilder.determineFileType("src/main/kotlin/com/example/MyKtService.kt", ""))
        assertEquals(ImplementFileType.VIEW_MARKUP, ImplementContextBuilder.determineFileType("src/main/webapp/WEB-INF/views/index.jsp", ""))
        assertEquals(ImplementFileType.VIEW_MARKUP, ImplementContextBuilder.determineFileType("index.html", ""))
        assertEquals(ImplementFileType.JAVASCRIPT, ImplementContextBuilder.determineFileType("script.js", ""))
        assertEquals(ImplementFileType.JAVASCRIPT, ImplementContextBuilder.determineFileType("app.ts", ""))
        
        // XML 2차 판정
        val mapperXml = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.UserMapper">
            </mapper>
        """.trimIndent()
        assertEquals(ImplementFileType.MYBATIS_XML, ImplementContextBuilder.determineFileType("mapper.xml", mapperXml))
        
        val springXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <beans xmlns="http://www.springframework.org/schema/beans">
            </beans>
        """.trimIndent()
        assertEquals(ImplementFileType.UNKNOWN, ImplementContextBuilder.determineFileType("applicationContext.xml", springXml))
        
        assertEquals(ImplementFileType.UNKNOWN, ImplementContextBuilder.determineFileType("README.md", ""))
    }
}
