package com.mranalyser

import com.mranalyser.application.llm.prompt.CrossFileReviewPrompt
import com.mranalyser.application.llm.prompt.FinalAssessmentPrompt
import com.mranalyser.application.llm.prompt.FindingValidationPrompt
import com.mranalyser.application.llm.prompt.LocalReviewPrompt
import com.mranalyser.application.llm.skill.PathGlob
import com.mranalyser.application.llm.skill.ReviewSkill
import com.mranalyser.application.llm.skill.ReviewSkillCatalog
import com.mranalyser.application.llm.skill.SkillTarget
import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.application.review.ClassifiedFile
import com.mranalyser.application.review.CrossFileReviewInput
import com.mranalyser.application.review.FinalAssessmentInput
import com.mranalyser.application.review.MergeRequestOverview
import com.mranalyser.application.review.ValidationInput
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity
import com.mranalyser.infrastructure.skill.ReviewSkillFileLoader
import com.mranalyser.support.MergeRequestFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReviewSkillTest {

    private val loader = ReviewSkillFileLoader()

    @Test
    fun `deve ler skills com frontmatter e ignorar o texto introdutorio`() {
        val skills = loader.parse(
            """
# Título do arquivo
Introdução que não vai ao modelo.

---
name: outbox
description: eventos pelo outbox
groups: [APPLICATION, messaging]
triggers: ["publish"]
stages: [local-review, VALIDATION]
priority: 7
---
Publicação direta após save é dual-write.

---
name: testes
groups: [TEST]
---
Não comente estilo de teste.
""".trimIndent()
        )

        assertEquals(listOf("outbox", "testes"), skills.map { it.name })
        val outbox = skills.first()
        assertEquals("eventos pelo outbox", outbox.description)
        assertEquals(setOf(ChangeGroup.APPLICATION, ChangeGroup.MESSAGING), outbox.groups)
        assertEquals(setOf(LlmPurpose.LOCAL_REVIEW, LlmPurpose.VALIDATION), outbox.stages)
        assertEquals(7, outbox.priority)
        assertEquals("Publicação direta após save é dual-write.", outbox.content)
        assertFalse(outbox.content.contains("Introdução"))
        assertEquals(ReviewSkill.DEFAULT_STAGES, skills[1].stages)
    }

    @Test
    fun `separador sem frontmatter valido deve permanecer como conteudo`() {
        val skills = loader.parse(
            """
---
name: a
---
antes
---
depois
""".trimIndent()
        )

        assertEquals(1, skills.size)
        assertEquals("antes\n---\ndepois", skills.single().content)
    }

    @Test
    fun `skill sem conteudo ou com valor desconhecido nao deve derrubar a leitura`() {
        val skills = loader.parse(
            """
---
name: vazia
---
---
name: ok
groups: [INEXISTENTE, TEST]
---
conteúdo
""".trimIndent()
        )

        assertEquals(listOf("ok"), skills.map { it.name })
        assertEquals(setOf(ChangeGroup.TEST), skills.single().groups)
    }

    @Test
    fun `aplicabilidade exige grupo caminho e gatilho quando declarados`() {
        val skill = ReviewSkill(
            name = "s",
            content = "c",
            groups = setOf(ChangeGroup.PERSISTENCE),
            paths = listOf("**/*.kt"),
            triggers = listOf("@Entity")
        )

        assertTrue(skill.appliesTo(LlmPurpose.LOCAL_REVIEW, SkillTarget("src/A.kt", ChangeGroup.PERSISTENCE, "@entity class A")))
        assertFalse(skill.appliesTo(LlmPurpose.LOCAL_REVIEW, SkillTarget("src/A.java", ChangeGroup.PERSISTENCE, "@Entity")))
        assertFalse(skill.appliesTo(LlmPurpose.LOCAL_REVIEW, SkillTarget("src/A.kt", ChangeGroup.API, "@Entity")))
        assertFalse(skill.appliesTo(LlmPurpose.LOCAL_REVIEW, SkillTarget("src/A.kt", ChangeGroup.PERSISTENCE, "class A")))
        assertFalse(skill.appliesTo(LlmPurpose.FINAL_ASSESSMENT, SkillTarget("src/A.kt", ChangeGroup.PERSISTENCE, "@Entity")))
    }

    @Test
    fun `glob com dupla estrela deve casar arquivo na raiz e em subdiretorio`() {
        val regex = PathGlob.toRegex("**/*.kt")

        assertTrue(regex.matches("Main.kt"))
        assertTrue(regex.matches("src/main/kotlin/Main.kt"))
        assertFalse(regex.matches("src/Main.java"))
        assertTrue(PathGlob.toRegex("generated/**").matches("generated/a/b.kt"))
        assertTrue(PathGlob.toRegex("*.lock").matches("yarn.lock"))
    }

    @Test
    fun `orcamento deve priorizar e omitir skill inteira em vez de cortar`() {
        val catalog = ReviewSkillCatalog(
            skills = listOf(
                ReviewSkill(name = "baixa", content = "b".repeat(60), priority = 1),
                ReviewSkill(name = "alta", content = "a".repeat(60), priority = 9)
            ),
            maxChars = 100
        )

        val selected = catalog.select(LlmPurpose.LOCAL_REVIEW, listOf(SkillTarget("A.kt", null, "")))

        assertEquals(listOf("alta"), selected.map { it.name })
    }

    @Test
    fun `skills devem entrar no review local e na validacao, mas nao no parecer final`() {
        val catalog = ReviewSkillCatalog(
            listOf(
                ReviewSkill(name = "skill-review", content = "REGRA-LOCAL", groups = setOf(ChangeGroup.APPLICATION)),
                ReviewSkill(name = "skill-validacao", content = "REGRA-VALIDACAO", stages = setOf(LlmPurpose.VALIDATION)),
                ReviewSkill(name = "skill-mensageria", content = "REGRA-MENSAGERIA", groups = setOf(ChangeGroup.MESSAGING))
            )
        )

        val local = LocalReviewPrompt(skills = catalog).build(chunkInput(), 1_000).user
        assertTrue(local.contains("SKILLS DO TIME"))
        assertTrue(local.contains("REGRA-LOCAL"))
        assertFalse(local.contains("REGRA-VALIDACAO"))
        assertFalse(local.contains("REGRA-MENSAGERIA"), "skill de outro grupo não deve gastar contexto")

        val validation = FindingValidationPrompt(skills = catalog).build(validationInput(), 1_000).user
        assertTrue(validation.contains("REGRA-VALIDACAO"))
        assertTrue(validation.contains("REGRA-LOCAL"))

        val crossFile = CrossFileReviewPrompt(skills = catalog).build(crossFileInput(), 1_000).user
        assertTrue(crossFile.contains("REGRA-LOCAL"))

        val final = FinalAssessmentPrompt().build(finalAssessmentInput(), 1_000).user
        assertFalse(final.contains("SKILLS DO TIME"))
    }

    @Test
    fun `sem skills aplicaveis o prompt nao deve ganhar secao vazia`() {
        val local = LocalReviewPrompt().build(chunkInput(), 1_000).user

        assertFalse(local.contains("SKILLS DO TIME"))
    }

    @Test
    fun `arquivo de skills versionado deve ser valido e caber no orcamento padrao`() {
        val skills = loader.load(File("skills/review-skills.md"))

        assertTrue(skills.size >= 10, "esperava as skills do arquivo versionado, obtive ${skills.map { it.name }}")
        assertEquals(skills.size, skills.map { it.name }.toSet().size, "nomes de skill duplicados")
        skills.forEach { skill ->
            assertTrue(skill.content.length <= 1_200, "skill '${skill.name}' grande demais para o num_ctx do Ollama")
            assertFalse(skill.content.contains("name:"), "frontmatter vazou para o corpo de '${skill.name}'")
        }

        // Pior caso realista: um chunk de aplicação que persiste, publica e mexe com valor.
        val target = SkillTarget(
            path = "src/main/kotlin/billing/application/InvoiceService.kt",
            group = ChangeGroup.APPLICATION,
            text = "@Transactional fun pay() { repository.save(invoice); publisher.publish(event); val amount: BigDecimal; logger.info(cpf) }"
        )
        val selected = ReviewSkillCatalog(skills).select(LlmPurpose.LOCAL_REVIEW, listOf(target))
        val applicable = skills.filter { it.appliesTo(LlmPurpose.LOCAL_REVIEW, target) }
        assertEquals(applicable.map { it.name }.toSet(), selected.map { it.name }.toSet(), "orçamento padrão cortou skills")
    }

    private fun files(): List<ClassifiedFile> =
        MergeRequestFixtures.transactionalOrderingMr().changes.map { change ->
            ClassifiedFile(change, ChangeGroup.APPLICATION, "ADD     84 |         provider.cancel(invoice.externalId)")
        }

    private fun overview(): MergeRequestOverview =
        MergeRequestOverview.from(MergeRequestFixtures.transactionalOrderingMr(), files())

    private fun chunkInput(): ChunkReviewInput = ChunkReviewInput(
        overview = overview(),
        chunkIndex = 1,
        chunkCount = 1,
        group = ChangeGroup.APPLICATION,
        files = files(),
        relatedContext = emptyList(),
        discussions = emptyList(),
        understanding = null,
        architecturalSignals = emptyList()
    )

    private fun candidate(): ReviewFinding = ReviewFinding(
        severity = Severity.MEDIUM,
        category = ReviewCategory.DATA_CONSISTENCY,
        file = files().first().path,
        line = 84,
        title = "Cancelamento antes da persistência",
        description = "provider.cancel antes do save",
        impact = null,
        recommendation = null,
        suggestedComment = null,
        confidence = 0.8
    )

    private fun validationInput(): ValidationInput = ValidationInput(
        overview = overview(),
        understanding = null,
        candidates = listOf(candidate()),
        relatedContext = emptyList(),
        discussions = emptyList(),
        evidenceExcerpts = emptyMap()
    )

    private fun crossFileInput(): CrossFileReviewInput = CrossFileReviewInput(
        overview = overview(),
        understanding = null,
        architecturalSignals = emptyList(),
        confirmedFindings = emptyList(),
        relationEdges = emptyList(),
        addedLinesByFile = emptyMap(),
        relatedContext = emptyList()
    )

    private fun finalAssessmentInput(): FinalAssessmentInput = FinalAssessmentInput(
        overview = overview(),
        understanding = null,
        architecturalSignals = emptyList(),
        findings = emptyList(),
        positivePoints = emptyList(),
        openQuestions = emptyList(),
        degraded = false,
        degradationReasons = emptyList()
    )
}
