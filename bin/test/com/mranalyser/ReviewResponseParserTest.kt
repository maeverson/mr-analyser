package com.mranalyser

import com.mranalyser.application.llm.parser.ReviewResponseParser
import com.mranalyser.application.llm.parser.StageResult
import com.mranalyser.application.llm.parser.ValidationDecision
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Item 38: o parsing não pode assumir que o modelo devolve JSON perfeito. */
class ReviewResponseParserTest {
    private val parser = ReviewResponseParser()

    @Test
    fun `deve extrair json cercado por markdown e prosa`() {
        val raw = """
            Claro! Aqui está a análise solicitada:

            ```json
            {"summary": "resumo", "findings": [], "questions": [], "positivePoints": []}
            ```

            Espero que ajude.
        """.trimIndent()

        val result = parser.parseLocalReview(raw)

        assertEquals("resumo", result.valueOrNull()?.summary)
    }

    @Test
    fun `deve remover bloco de raciocinio e virgula sobrando`() {
        val raw = """
            <think>preciso decidir a severidade</think>
            {
              "summary": "resumo",
              "findings": [],
              "questions": ["pergunta"],
            }
        """.trimIndent()

        val result = parser.parseLocalReview(raw)

        assertEquals(listOf("pergunta"), result.valueOrNull()?.questions)
    }

    @Test
    fun `deve escolher o objeto valido quando ha varios na resposta`() {
        val raw = """
            {"note": "ignorar este"}
            {"summary": "resumo correto", "findings": [], "questions": [], "positivePoints": []}
        """.trimIndent()

        assertEquals("resumo correto", parser.parseLocalReview(raw).valueOrNull()?.summary)
    }

    @Test
    fun `deve tolerar tipos errados em line e confidence`() {
        val raw = """
            {
              "summary": "resumo",
              "findings": [
                {
                  "title": "Problema",
                  "description": "descrição suficientemente longa para o filtro",
                  "severity": "MAJOR",
                  "category": "correctness",
                  "type": "defect",
                  "line": "linha 84",
                  "confidence": 85
                }
              ]
            }
        """.trimIndent()

        val finding = parser.parseLocalReview(raw).valueOrNull()!!.findings.single()

        assertEquals(84, finding.line, "line em texto deve ser recuperado")
        assertEquals(0.85, finding.confidence, "confidence em percentual deve virar fração")
        assertEquals(Severity.HIGH, finding.severity, "MAJOR deve ser mapeado para HIGH")
        assertEquals(ReviewCategory.BUG, finding.category)
        assertEquals(FindingType.BUG, finding.type)
    }

    @Test
    fun `deve usar defaults seguros para enums desconhecidos em vez de descartar o finding`() {
        val raw = """
            {
              "summary": "resumo",
              "findings": [
                {
                  "title": "Problema",
                  "description": "descrição suficientemente longa para o filtro",
                  "severity": "SUPER_URGENTE",
                  "category": "ALGO_INEXISTENTE",
                  "type": "???",
                  "confidence": 0.9
                }
              ]
            }
        """.trimIndent()

        val findings = parser.parseLocalReview(raw).valueOrNull()!!.findings

        assertEquals(1, findings.size, "enum desconhecido não deve custar o finding inteiro")
        assertEquals(Severity.MEDIUM, findings.single().severity)
        assertEquals(FindingType.RISK, findings.single().type)
    }

    @Test
    fun `deve descartar finding sem titulo`() {
        val raw = """
            {
              "summary": "resumo",
              "findings": [
                {"description": "sem título"},
                {"title": "Com título", "description": "descrição longa o suficiente"}
              ]
            }
        """.trimIndent()

        val result = parser.parseLocalReview(raw).valueOrNull()!!

        assertEquals(1, result.findings.size)
        assertEquals(1, result.droppedFindings)
    }

    @Test
    fun `deve normalizar placeholders textuais para nulo`() {
        val raw = """
            {
              "summary": "resumo",
              "findings": [
                {
                  "title": "Problema",
                  "description": "descrição longa o suficiente para passar",
                  "evidence": "N/A",
                  "failureScenario": "null",
                  "impact": "nenhum"
                }
              ]
            }
        """.trimIndent()

        val finding = parser.parseLocalReview(raw).valueOrNull()!!.findings.single()

        assertEquals(null, finding.evidence)
        assertEquals(null, finding.failureScenario)
        assertEquals(null, finding.impact)
    }

    @Test
    fun `deve falhar de forma explicita quando nao ha json`() {
        val result = parser.parseLocalReview("Não consigo analisar este diff.")

        assertInstanceOf(StageResult.Failure::class.java, result)
        assertTrue(result.failureOrNull()!!.contains("nenhum objeto JSON"))
    }

    @Test
    fun `deve falhar quando o json nao possui as chaves esperadas`() {
        val result = parser.parseLocalReview("""{"foo": "bar"}""")

        assertInstanceOf(StageResult.Failure::class.java, result)
    }

    @Test
    fun `deve interpretar veredito de validacao e tratar decisao ilegivel como rebaixamento`() {
        val raw = """
            {"verdicts": [
              {"id": "F1", "decision": "DISCARD", "reason": "sem evidência"},
              {"id": "F2", "decision": "algo_inesperado", "reason": "?"},
              {"id": "F3", "decision": "KEEP", "severity": "MEDIUM", "confidence": 0.8, "blocking": true}
            ]}
        """.trimIndent()

        val verdicts = parser.parseValidation(raw).valueOrNull()!!.verdicts

        assertEquals(ValidationDecision.DISCARD, verdicts[0].decision)
        assertEquals(
            ValidationDecision.DOWNGRADE_TO_QUESTION,
            verdicts[1].decision,
            "decisão ilegível deve rebaixar, não descartar nem confirmar"
        )
        assertEquals(ValidationDecision.KEEP, verdicts[2].decision)
        assertEquals(true, verdicts[2].blocking)
    }

    @Test
    fun `deve interpretar entendimento e parecer final`() {
        val understanding = parser.parseUnderstanding(
            """{"intent": "objetivo", "narrative": "narrativa", "blastRadius": "wide"}"""
        ).valueOrNull()!!.understanding

        assertEquals("objetivo", understanding.intent)
        assertEquals("CROSS_SERVICE", understanding.blastRadius.name)

        val assessment = parser.parseFinalAssessment(
            """{"opinion": "parecer", "analysisConfidence": "alta", "recommendation": "changes_requested"}"""
        ).valueOrNull()!!

        assertEquals("parecer", assessment.opinion.opinion)
        assertEquals("HIGH", assessment.opinion.analysisConfidence.name)
        assertEquals("REQUEST_CHANGES", assessment.suggestedRecommendation?.name)
    }
}
