package net.ib.ixpert.ops.wuwagent.engine

import org.junit.Assert.*
import org.junit.Test

class ActionBasedEditEngineTest {

    private val engine = ActionBasedEditEngine()

    @Test
    fun testParseActions() {
        val response = """
            [MODIFIED_SIGNATURES]
            1. `void newMethod()`

            [ACTION: ADD_IMPORT]
            [CODE]
            import java.util.ArrayList;
            [/CODE]

            [ACTION: INSERT_AFTER]
            [ANCHOR: existingMethod]
            [CODE]
                public void newMethod() {
                    // Logic
                }
            [/CODE]
        """.trimIndent()

        val actions = engine.parse(response)
        assertEquals(2, actions.size)
        assertEquals(ActionBasedEditEngine.Action.ADD_IMPORT, actions[0].action)
        assertEquals(ActionBasedEditEngine.Action.INSERT_AFTER, actions[1].action)
        assertEquals("existingMethod", actions[1].anchor)
        assertTrue(actions[1].code.contains("public void newMethod()"))
    }

    @Test
    fun testApplyInsertAfter() {
        val source = """
            public class Test {
                public void existingMethod() {
                    System.out.println("hello");
                }
            }
        """.trimIndent()

        val action = ActionBasedEditEngine.EditAction(
            action = ActionBasedEditEngine.Action.INSERT_AFTER,
            anchor = "existingMethod",
            code = "    public void newMethod() {}",
            index = 0
        )

        val result = engine.apply(source, listOf(action))
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("existingMethod"))
        assertTrue(result.updatedContent!!.contains("newMethod"))
        // Check order: newMethod should be after existingMethod's closing brace
        val existingIdx = result.updatedContent!!.indexOf("existingMethod")
        val newIdx = result.updatedContent!!.indexOf("newMethod")
        assertTrue(newIdx > existingIdx)
    }

    @Test
    fun testApplyAddImport() {
        val source = """
            package com.test;

            import java.util.List;

            public class Test {}
        """.trimIndent()

        val action = ActionBasedEditEngine.EditAction(
            action = ActionBasedEditEngine.Action.ADD_IMPORT,
            anchor = "",
            code = "import java.util.ArrayList;",
            index = 0
        )

        val result = engine.apply(source, listOf(action))
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("import java.util.ArrayList;"))
        assertTrue(result.updatedContent!!.contains("import java.util.List;"))
    }

    @Test
    fun testFuzzyAnchorMatching() {
        val source = """
            public class Service {
                @Override
                public List<String> selectList(Param p) {
                    return null;
                }
            }
        """.trimIndent()

        // Anchor is just the method name
        val action = ActionBasedEditEngine.EditAction(
            action = ActionBasedEditEngine.Action.INSERT_AFTER,
            anchor = "selectList",
            code = "    // comment",
            index = 0
        )

        val result = engine.apply(source, listOf(action))
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("// comment"))
    }

    @Test
    fun testReplaceMethod() {
        val source = """
            public class Test {
                public void oldMethod() {
                    // old logic
                }
            }
        """.trimIndent()

        val action = ActionBasedEditEngine.EditAction(
            action = ActionBasedEditEngine.Action.REPLACE_METHOD,
            anchor = "oldMethod",
            code = "    public void oldMethod() {\n        // new logic\n    }",
            index = 0
        )

        val result = engine.apply(source, listOf(action))
        assertTrue(result.success)
        assertTrue(result.updatedContent!!.contains("// new logic"))
        assertFalse(result.updatedContent!!.contains("// old logic"))
    }
}
