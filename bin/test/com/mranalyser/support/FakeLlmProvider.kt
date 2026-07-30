package com.mranalyser.support

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse
import java.util.Collections

/**
 * Provider de teste programável por etapa.
 *
 * Existe porque o pipeline agora tem cinco etapas com prompts distintos, e cada teste precisa
 * controlar apenas a resposta da etapa que está exercitando, deixando as demais em falha
 * controlada (o que também exercita o caminho de análise parcial).
 */
class FakeLlmProvider(
    private val responses: Map<LlmPurpose, (LlmRequest) -> LlmResponse> = emptyMap(),
    private val default: (LlmRequest) -> LlmResponse = {
        LlmResponse.failed("etapa ${it.purpose.label} não programada no teste")
    }
) : LlmProvider {
    override val name: String = "fake"

    private val recorded = Collections.synchronizedList(mutableListOf<LlmRequest>())

    val requests: List<LlmRequest> get() = synchronized(recorded) { recorded.toList() }

    fun requestsFor(purpose: LlmPurpose): List<LlmRequest> = requests.filter { it.purpose == purpose }

    override suspend fun complete(request: LlmRequest): LlmResponse {
        recorded += request
        return (responses[request.purpose] ?: default)(request)
    }

    companion object {
        fun replying(vararg pairs: Pair<LlmPurpose, String>): FakeLlmProvider = FakeLlmProvider(
            responses = pairs.associate { (purpose, body) -> purpose to { _: LlmRequest -> LlmResponse(body) } }
        )

        fun alwaysFailing(reason: String = "provider indisponível"): FakeLlmProvider =
            FakeLlmProvider(default = { LlmResponse.failed(reason) })
    }
}

/** Respostas JSON reutilizáveis, no formato exigido por cada prompt. */
object FakeLlmResponses {

    fun understanding(
        intent: String = "Adiciona cancelamento de invoice.",
        narrative: String = "O MR adiciona o fluxo de cancelamento e integra com o provider externo.",
        blastRadius: String = "SERVICE"
    ): String = """
        {
          "intent": "$intent",
          "narrative": "$narrative",
          "behaviourChanges": ["invoice passa a poder ser cancelada"],
          "newExecutionPaths": ["cancel -> provider -> persistência"],
          "contractChanges": [],
          "affectedDependencies": ["Billing Provider"],
          "blastRadius": "$blastRadius",
          "blastRadiusRationale": "altera comportamento observável do serviço",
          "intentDiscrepancy": null
        }
    """.trimIndent()

    fun localReview(
        summary: String = "Fluxo de cancelamento adicionado na camada de aplicação.",
        findingsJson: String = transactionalFinding(),
        questions: String = """["Existe retry configurado no client?"]""",
        positives: String = """["Erros do provider são convertidos em erro de aplicação"]"""
    ): String = """
        {
          "summary": "$summary",
          "findings": [$findingsJson],
          "questions": $questions,
          "positivePoints": $positives,
          "suggestedRecommendation": "REQUEST_CHANGES"
        }
    """.trimIndent()

    fun transactionalFinding(
        file: String = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
        line: Int = 84
    ): String = """
        {
          "type": "BUG",
          "severity": "HIGH",
          "category": "DATA_CONSISTENCY",
          "scope": "INTRODUCED",
          "file": "$file",
          "line": $line,
          "title": "Cancelamento externo confirmado antes da persistência local",
          "description": "O provider é chamado antes da atualização da invoice.",
          "evidence": "$file:$line chama provider.cancel() antes de repository.save().",
          "failureScenario": "1. provider.cancel() executa com sucesso\n2. repository.save() falha\n3. provider considera a invoice cancelada\n4. aplicação mantém a invoice ativa",
          "impact": "Inconsistência entre o provider e o estado local da invoice.",
          "recommendation": "Tratar o cenário explicitamente com compensação ou idempotência.",
          "blocking": true,
          "commentType": "BLOCKER",
          "suggestedComment": "Neste fluxo o cancelamento externo acontece antes da atualização local. Como tratamos o cenário em que o provider confirma e o save() falha depois?",
          "componentsAffected": ["InvoiceCancellationService", "BillingProviderClient"],
          "relatedFiles": ["src/main/kotlin/billing/integration/BillingProviderClient.kt"],
          "confidence": 0.9
        }
    """.trimIndent()

    /** Finding sem evidência: usado para exercitar a EvidencePolicy. */
    fun unsupportedFinding(): String = """
        {
          "type": "RISK",
          "severity": "HIGH",
          "category": "RELIABILITY",
          "file": "src/main/kotlin/customers/application/RegisterCustomerUseCase.kt",
          "title": "Provavelmente falta retry na chamada ao score",
          "description": "A chamada externa aparenta não ter política de retry configurada.",
          "impact": "Indisponibilidade transitória do serviço de score derruba o cadastro.",
          "recommendation": "Avaliar retry com backoff.",
          "blocking": true,
          "confidence": 0.7
        }
    """.trimIndent()

    fun validation(vararg verdicts: String): String = """
        {"verdicts": [${verdicts.joinToString(",")}]}
    """.trimIndent()

    fun verdict(
        id: String,
        decision: String,
        severity: String? = null,
        confidence: Double? = null,
        blocking: Boolean? = null,
        evidence: String? = null,
        failureScenario: String? = null
    ): String = buildString {
        append("""{"id": "$id", "decision": "$decision", "reason": "veredito de teste"""")
        severity?.let { append(""", "severity": "$it"""") }
        confidence?.let { append(""", "confidence": $it""") }
        blocking?.let { append(""", "blocking": $it""") }
        evidence?.let { append(""", "evidence": "$it"""") }
        failureScenario?.let { append(""", "failureScenario": "$it"""") }
        append("}")
    }

    fun crossFileReview(
        summary: String = "A alteração cobre producer e consumer do mesmo evento.",
        findingsJson: String = "",
        invalidated: String = "[]"
    ): String = """
        {
          "summary": "$summary",
          "crossFileFindings": [$findingsJson],
          "invalidatedFindings": $invalidated,
          "questions": [],
          "positivePoints": []
        }
    """.trimIndent()

    fun contractMismatchFinding(): String = """
        {
          "type": "BUG",
          "severity": "HIGH",
          "category": "API_CONTRACT",
          "file": "src/main/kotlin/ledger/messaging/PaymentEventConsumer.kt",
          "line": 11,
          "title": "Consumer lê campo inexistente no evento publicado",
          "description": "O producer publica amountCents e o consumer lê amount.",
          "evidence": "PaymentEventProducer.kt:14 publica amountCents; PaymentEventConsumer.kt:11 lê event.amount.",
          "failureScenario": "1. producer publica PaymentEvent com amountCents\n2. consumer desserializa e lê amount\n3. valor chega nulo ou zerado no ledger",
          "impact": "Lançamento contábil com valor incorreto.",
          "recommendation": "Alinhar o nome do campo entre producer e consumer.",
          "blocking": true,
          "commentType": "BLOCKER",
          "suggestedComment": "O producer publica amountCents e aqui lemos event.amount. Esses nomes estão alinhados no contrato do evento?",
          "componentsAffected": ["PaymentEventProducer", "PaymentEventConsumer"],
          "confidence": 0.92
        }
    """.trimIndent()

    fun finalAssessment(
        opinion: String = "Existe risco concreto de inconsistência entre o provider e o estado local.",
        mainRisk: String? = "Inconsistência entre cancelamento externo e persistência da invoice.",
        confidence: String = "HIGH",
        recommendation: String = "REQUEST_CHANGES"
    ): String = """
        {
          "opinion": "$opinion",
          "mainRisk": ${mainRisk?.let { "\"$it\"" } ?: "null"},
          "analysisConfidence": "$confidence",
          "recommendation": "$recommendation",
          "questions": [],
          "positivePoints": []
        }
    """.trimIndent()
}
