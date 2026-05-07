package net.ib.ixpert.ops.wuwagent.engine

import org.junit.Assert.*
import org.junit.Test

class SearchReplaceEngineTest {

    private val engine = SearchReplaceEngine()

    @Test
    fun `test exact match parsing and apply`() {
        val original = """
            public class Test {
                public void hello() {
                    System.out.println("Hello");
                }
            }
        """.trimIndent()

        val response = """
            [FILE: Test.java]
            <<<<<<< SEARCH
                public void hello() {
                    System.out.println("Hello");
                }
            =======
                public void hello(String name) {
                    System.out.println("Hello, " + name);
                }
            >>>>>>> REPLACE
        """.trimIndent()

        val blocks = engine.parse(response)
        assertEquals(1, blocks.size)
        assertEquals("Test.java", blocks[0].filePath)

        val result = engine.apply(original, blocks)
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("public void hello(String name)"))
        assertTrue(result.updatedContent!!.contains("Hello, "))
    }

    @Test
    fun `test whitespace normalization match`() {
        val original = """
            public void test() {
                int a = 1;    // some spaces here
                int b = 2;
            }
        """.trimIndent()

        // LLM이 줄 끝 공백을 생략하고 보낸 상황 가정
        val response = """
            <<<<<<< SEARCH
                public void test() {
                    int a = 1;
                    int b = 2;
                }
            =======
                public void test() {
                    int a = 100;
                    int b = 200;
                }
            >>>>>>> REPLACE
        """.trimIndent()

        val blocks = engine.parse(response)
        val result = engine.apply(original, blocks)
        
        assertTrue("공백 정규화로 매칭되어야 함", result.success)
        assertTrue(result.updatedContent!!.contains("int a = 100;"))
    }

    @Test
    fun `test multiple blocks apply`() {
        val original = """
            class Multi {
                void one() { }
                void two() { }
                void three() { }
            }
        """.trimIndent()

        val response = """
            <<<<<<< SEARCH
                void one() { }
            =======
                void first() { }
            >>>>>>> REPLACE
            
            <<<<<<< SEARCH
                void three() { }
            =======
                void last() { }
            >>>>>>> REPLACE
        """.trimIndent()

        val blocks = engine.parse(response)
        assertEquals(2, blocks.size)

        val result = engine.apply(original, blocks)
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("void first()"))
        assertTrue(result.updatedContent!!.contains("void last()"))
        assertTrue(result.updatedContent!!.contains("void two()"))
    }

    @Test
    fun `test anchor matching`() {
        val original = """
            public void longMethod() {
                System.out.println("Line 1");
                System.out.println("Line 2");
                System.out.println("Line 3");
                System.out.println("Line 4");
                System.out.println("Line 5");
            }
        """.trimIndent()

        // 중간 줄을 생략하거나 미세하게 틀려도 첫/마지막 줄로 매칭
        val response = """
            <<<<<<< SEARCH
            public void longMethod() {
                // 중간 내용 대충...
                System.out.println("Line 5");
            }
            =======
            public void longMethod() {
                System.out.println("Simplified");
            }
            >>>>>>> REPLACE
        """.trimIndent()

        val blocks = engine.parse(response)
        val result = engine.apply(original, blocks)
        assertTrue("Anchor 매칭으로 성공해야 함", result.success)
        assertFalse(result.updatedContent!!.contains("Line 3"))
    }

    @Test
    fun `test validation failure - brace mismatch`() {
        val original = "public class A { }"
        val updated = "public class A { " // 닫는 브레이스 누락

        val validation = engine.validate(original, updated)
        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.contains("Brace mismatch") })
    }
}
