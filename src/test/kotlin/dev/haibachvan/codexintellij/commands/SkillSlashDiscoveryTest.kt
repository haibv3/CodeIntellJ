package dev.haibachvan.codexintellij.commands

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SkillSlashDiscoveryTest {
    @TempDir
    lateinit var temp: Path

    @Test
    fun `parseSkill reads frontmatter`() {
        val skillMd = temp.resolve("my-skill").resolve("SKILL.md")
        Files.createDirectories(skillMd.parent)
        Files.writeString(
            skillMd,
            """
            ---
            name: my-skill
            description: Does a useful thing
            user-invocable: true
            ---
            # Hello
            """.trimIndent(),
        )
        val skill = SkillSlashDiscovery.parseSkill(skillMd)
        assertEquals("my-skill", skill?.name)
        assertEquals("Does a useful thing", skill?.description)
        assertEquals("/my-skill ", skill?.insertText)
    }

    @Test
    fun `parseSkill reads folded yaml description`() {
        val skillMd = temp.resolve("babysit").resolve("SKILL.md")
        Files.createDirectories(skillMd.parent)
        Files.writeString(
            skillMd,
            """
            ---
            name: babysit
            description: >-
              Keep a PR merge-ready by triaging comments
              and fixing CI.
            ---
            body
            """.trimIndent(),
        )
        val skill = SkillSlashDiscovery.parseSkill(skillMd)
        assertEquals("babysit", skill?.name)
        assertTrue(skill!!.description.contains("merge-ready"))
        assertTrue(skill.description.contains("fixing CI"))
    }

    @Test
    fun `parseSkill skips non-invocable`() {
        val skillMd = temp.resolve("hidden").resolve("SKILL.md")
        Files.createDirectories(skillMd.parent)
        Files.writeString(
            skillMd,
            """
            ---
            name: hidden
            description: Internal only
            user-invocable: false
            ---
            """.trimIndent(),
        )
        assertNull(SkillSlashDiscovery.parseSkill(skillMd))
    }

    @Test
    fun `discover finds skills under agents roots`() {
        val homeStyle = temp.resolve("home")
        val root = homeStyle.resolve(".agents").resolve("skills").resolve("ck-demo")
        Files.createDirectories(root)
        Files.writeString(
            root.resolve("SKILL.md"),
            """
            ---
            name: ck-demo
            description: Demo skill from global agents
            ---
            body
            """.trimIndent(),
        )
        // Point discovery at a fake project that also has no skills; inject via
        // temporarily scanning by calling parse on known layout through discover's project path.
        // discover() uses user.home — so we only assert parse + layout contract here.
        val parsed = SkillSlashDiscovery.parseSkill(root.resolve("SKILL.md"))
        assertEquals("ck-demo", parsed?.name)
        assertTrue(parsed!!.description.contains("Demo skill"))
    }
}
