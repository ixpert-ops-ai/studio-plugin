package net.ib.ixpert.ops.wuwagent.agent

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ImplementTrimmerTest {

    @Test
    fun testTrimMybatisXml() {
        val xmlContent = """
            <?xml version="1.0" encoding="UTF-8" ?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.UserMapper">
                <select id="getUser" resultType="User">
                    SELECT * FROM users WHERE id = #{id}
                </select>
                <update id="updateUser">
                    UPDATE users SET name = #{name} WHERE id = #{id}
                </update>
                <delete id="deleteUser">
                    DELETE FROM users WHERE id = #{id}
                </delete>
            </mapper>
        """.trimIndent()

        val trimmed = ImplementTrimmer.trimMybatisXml(xmlContent, listOf("updateUser"))
        println("TRIMMED START")
        println(trimmed)
        println("TRIMMED END")

        assertTrue(trimmed.contains("""<update id="updateUser">"""))
        assertTrue(trimmed.contains("""UPDATE users SET name"""))
        assertFalse(trimmed.contains("""SELECT * FROM users"""))
        assertFalse(trimmed.contains("""DELETE FROM users"""))
    }
}
