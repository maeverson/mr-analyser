package com.mranalyser.application.llm.parser

import com.mranalyser.domain.model.AnalysisConfidence
import com.mranalyser.domain.model.BlastRadius
import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingScope
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.MergeRecommendation
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.Severity

/**
 * Conversão tolerante de enums vindos do LLM (item 38).
 *
 * A V1 **descartava o finding inteiro** quando `severity` ou `category` não casava exatamente
 * com o enum — um erro de digitação do modelo custava um achado potencialmente válido.
 * Aqui há normalização, aliases para as variações que os modelos realmente produzem
 * (incluindo português), e um default seguro por campo.
 */
object EnumCoercion {

    private fun normalize(raw: String?): String? = raw
        ?.trim()
        ?.trim('"', '\'', '.', ',')
        ?.uppercase()
        ?.replace(' ', '_')
        ?.replace('-', '_')
        ?.takeIf { it.isNotBlank() }

    fun severity(raw: String?, default: Severity = Severity.MEDIUM): Severity {
        val key = normalize(raw) ?: return default
        return runCatching { Severity.valueOf(key) }.getOrNull() ?: when (key) {
            "BLOCKER", "FATAL", "SEVERE", "CRITICO", "CRÍTICO" -> Severity.CRITICAL
            "MAJOR", "ALTA", "ALTO", "IMPORTANT" -> Severity.HIGH
            "MINOR", "MODERATE", "MEDIA", "MÉDIA", "MEDIO", "MÉDIO", "WARNING", "WARN" -> Severity.MEDIUM
            "TRIVIAL", "BAIXA", "BAIXO", "NIT", "NITPICK" -> Severity.LOW
            "INFORMATIONAL", "INFORMATION", "NOTE", "NOTICE", "OBSERVACAO", "OBSERVAÇÃO" -> Severity.INFO
            else -> default
        }
    }

    fun category(raw: String?, default: ReviewCategory = ReviewCategory.DESIGN): ReviewCategory {
        val key = normalize(raw) ?: return default
        return runCatching { ReviewCategory.valueOf(key) }.getOrNull() ?: when (key) {
            "CORRECTNESS", "LOGIC", "DEFECT", "ERROR", "FUNCTIONAL" -> ReviewCategory.BUG
            "BUSINESS", "BUSINESS_LOGIC", "DOMAIN", "DOMAIN_RULE", "REGRA_DE_NEGOCIO" -> ReviewCategory.BUSINESS_RULE
            "SECURITY_RISK", "VULNERABILITY", "SEGURANCA", "SEGURANÇA" -> ReviewCategory.SECURITY
            "ARCH", "STRUCTURE", "COUPLING", "ARQUITETURA" -> ReviewCategory.ARCHITECTURE
            "RESILIENCE", "RESILIENCY", "ERROR_HANDLING", "AVAILABILITY", "CONFIABILIDADE" ->
                ReviewCategory.RELIABILITY
            "TRANSACTIONAL", "TRANSACTIONS", "TRANSACAO", "TRANSAÇÃO" -> ReviewCategory.TRANSACTION
            "CONSISTENCY", "DATA_INTEGRITY", "INTEGRITY", "CONSISTENCIA" -> ReviewCategory.DATA_CONSISTENCY
            "THREAD_SAFETY", "RACE_CONDITION", "PARALLELISM", "CONCORRENCIA" -> ReviewCategory.CONCURRENCY
            "CONTRACT", "API", "BACKWARD_COMPATIBILITY", "SCHEMA" -> ReviewCategory.API_CONTRACT
            "LOGGING", "MONITORING", "METRICS", "TRACING", "OBSERVABILIDADE" -> ReviewCategory.OBSERVABILITY
            "OPERATIONAL", "OPS", "DEPLOYMENT", "OPERACAO", "OPERAÇÃO" -> ReviewCategory.OPERATIONS
            "TESTS", "TEST", "TEST_COVERAGE", "TESTING", "TESTES" -> ReviewCategory.TESTABILITY
            "READABILITY", "COMPLEXITY", "TECH_DEBT", "MANUTENIBILIDADE" -> ReviewCategory.MAINTAINABILITY
            "STYLE", "FORMATTING", "NAMING", "CONVENTION", "ESTILO" -> ReviewCategory.CODE_STYLE
            "DOCS", "DOCUMENTACAO", "DOCUMENTAÇÃO" -> ReviewCategory.DOCUMENTATION
            "SCALABILITY", "EFFICIENCY", "LATENCY" -> ReviewCategory.PERFORMANCE
            else -> default
        }
    }

    fun findingType(raw: String?, default: FindingType = FindingType.RISK): FindingType {
        val key = normalize(raw) ?: return default
        return runCatching { FindingType.valueOf(key) }.getOrNull() ?: when (key) {
            "DEFECT", "ERROR", "INCORRECT", "CORRECTNESS" -> FindingType.BUG
            "POTENTIAL_ISSUE", "HAZARD", "RISCO" -> FindingType.RISK
            "SMELL", "CODE_SMELL", "TECH_DEBT", "DESENHO" -> FindingType.DESIGN
            "STRUCTURAL", "ARCH", "ARQUITETURA" -> FindingType.ARCHITECTURE
            "CLARIFICATION", "DOUBT", "ASK", "QUESTIONAMENTO", "PERGUNTA" -> FindingType.QUESTION
            "IMPROVEMENT", "NIT", "NITPICK", "SUGESTAO", "SUGESTÃO" -> FindingType.SUGGESTION
            else -> default
        }
    }

    fun commentType(raw: String?): CommentType? {
        val key = normalize(raw) ?: return null
        return runCatching { CommentType.valueOf(key) }.getOrNull() ?: when (key) {
            "BLOCKING", "MUST_FIX", "BLOQUEADOR" -> CommentType.BLOCKER
            "ASK", "CLARIFICATION", "PERGUNTA", "QUESTIONAMENTO" -> CommentType.QUESTION
            "NIT", "NITPICK", "IMPROVEMENT", "SUGESTAO", "SUGESTÃO" -> CommentType.SUGGESTION
            "NOTE", "INFO", "FYI", "OBSERVACAO", "OBSERVAÇÃO" -> CommentType.OBSERVATION
            "COMPLIMENT", "POSITIVE", "ELOGIO" -> CommentType.PRAISE
            else -> null
        }
    }

    fun scope(raw: String?, default: FindingScope = FindingScope.INTRODUCED): FindingScope {
        val key = normalize(raw) ?: return default
        return runCatching { FindingScope.valueOf(key) }.getOrNull() ?: when (key) {
            "NEW", "INTRODUZIDO", "THIS_MR" -> FindingScope.INTRODUCED
            "EXISTING", "PREEXISTING", "LEGACY", "PRE_EXISTENTE", "PREEXISTENTE" -> FindingScope.PRE_EXISTING
            else -> default
        }
    }

    fun recommendation(raw: String?): MergeRecommendation? {
        val key = normalize(raw) ?: return null
        return runCatching { MergeRecommendation.valueOf(key) }.getOrNull() ?: when (key) {
            "APPROVED", "LGTM", "APROVAR", "APROVADO" -> MergeRecommendation.APPROVE
            "APPROVE_WITH_COMMENTS", "APPROVE_WITH_NITS", "APROVAR_COM_SUGESTOES" ->
                MergeRecommendation.APPROVE_WITH_SUGGESTIONS
            "DISCUSS", "DISCUSSION", "NEEDS_CLARIFICATION", "DISCUTIR" -> MergeRecommendation.NEEDS_DISCUSSION
            "REJECT", "REJECTED", "CHANGES_REQUESTED", "BLOCK", "SOLICITAR_AJUSTES" ->
                MergeRecommendation.REQUEST_CHANGES
            else -> null
        }
    }

    fun blastRadius(raw: String?, default: BlastRadius = BlastRadius.UNKNOWN): BlastRadius {
        val key = normalize(raw) ?: return default
        return runCatching { BlastRadius.valueOf(key) }.getOrNull() ?: when (key) {
            "SMALL", "NARROW", "ISOLATED", "LOCAL_ONLY" -> BlastRadius.LOCAL
            "PACKAGE", "COMPONENT", "MODULO", "MÓDULO" -> BlastRadius.MODULE
            "APPLICATION", "SYSTEM", "SERVICO", "SERVIÇO" -> BlastRadius.SERVICE
            "WIDE", "GLOBAL", "EXTERNAL", "CROSS_SERVICES", "MULTI_SERVICE" -> BlastRadius.CROSS_SERVICE
            else -> default
        }
    }

    fun analysisConfidence(raw: String?, default: AnalysisConfidence = AnalysisConfidence.MEDIUM): AnalysisConfidence {
        val key = normalize(raw) ?: return default
        return runCatching { AnalysisConfidence.valueOf(key) }.getOrNull() ?: when (key) {
            "ALTA", "ALTO", "STRONG" -> AnalysisConfidence.HIGH
            "MEDIA", "MÉDIA", "MODERATE" -> AnalysisConfidence.MEDIUM
            "BAIXA", "BAIXO", "WEAK", "LIMITED" -> AnalysisConfidence.LOW
            else -> default
        }
    }

    fun confidence(raw: Double?, default: Double = 0.60): Double {
        val value = raw ?: return default
        if (value.isNaN() || value.isInfinite()) {
            return default
        }
        // Modelos às vezes devolvem percentual (85) em vez de fração (0.85).
        val normalized = if (value > 1.0 && value <= 100.0) value / 100.0 else value
        return normalized.coerceIn(0.0, 1.0)
    }
}
