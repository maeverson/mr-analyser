package com.mranalyser.support

import com.mranalyser.domain.model.Author
import com.mranalyser.domain.model.Commit
import com.mranalyser.domain.model.Discussion
import com.mranalyser.domain.model.DiscussionNote
import com.mranalyser.domain.model.FileChange
import com.mranalyser.domain.model.MergeRequest

/**
 * MRs representativos usados pelos testes de pipeline.
 *
 * Cada fixture corresponde a um cenário da especificação (item 37): bug real, falso positivo,
 * problema transacional, ausência de testes, problema cross-file, MR correto e risco que deve
 * virar questionamento.
 */
object MergeRequestFixtures {

    fun mergeRequest(
        iid: Long = 1,
        title: String = "MR de teste",
        description: String? = "descrição",
        changes: List<FileChange>,
        commits: List<Commit> = listOf(commit("feat: alteração de teste")),
        discussions: List<Discussion> = emptyList(),
        projectPath: String? = "grupo/projeto"
    ): MergeRequest = MergeRequest(
        id = iid * 100,
        iid = iid,
        title = title,
        description = description,
        author = Author(name = "Autor de Teste", username = "autor"),
        sourceBranch = "feature/teste",
        targetBranch = "main",
        changes = changes,
        commits = commits,
        discussions = discussions,
        projectPath = projectPath,
        webUrl = "https://gitlab.example.com/grupo/projeto/-/merge_requests/$iid"
    )

    fun commit(message: String, sha: String = "abcdef1234567890"): Commit =
        Commit(sha = sha, message = message, author = Author(name = "Autor de Teste"))

    fun discussion(
        body: String,
        resolved: Boolean = false,
        file: String? = null,
        line: Int? = null,
        system: Boolean = false
    ): Discussion = Discussion(
        id = "d-${body.hashCode()}",
        notes = listOf(
            DiscussionNote(
                id = "n-${body.hashCode()}",
                author = Author(name = "Revisor"),
                body = body,
                system = system,
                resolvable = true,
                resolved = resolved,
                file = file,
                line = line
            )
        )
    )

    fun change(
        path: String,
        diff: String,
        added: Boolean = false,
        deleted: Boolean = false,
        renamed: Boolean = false,
        generated: Boolean = false,
        oldPath: String = path
    ): FileChange {
        val linesAdded = diff.lineSequence().count { it.startsWith("+") && !it.startsWith("+++") }
        val linesRemoved = diff.lineSequence().count { it.startsWith("-") && !it.startsWith("---") }
        return FileChange(
            oldPath = oldPath,
            newPath = path,
            added = added,
            deleted = deleted,
            renamed = renamed,
            diff = diff,
            linesAdded = linesAdded,
            linesRemoved = linesRemoved,
            generated = generated
        )
    }

    /** Cenário 1 e 3: chamada externa confirmada antes da persistência local. */
    fun transactionalOrderingMr(): MergeRequest = mergeRequest(
        iid = 101,
        title = "Add invoice cancellation",
        description = "Adiciona cancelamento de invoice com integração ao provider de billing.",
        changes = listOf(
            change(
                path = "src/main/kotlin/billing/application/InvoiceCancellationService.kt",
                diff = """
                    @@ -80,6 +80,14 @@ class InvoiceCancellationService(
                         fun cancel(invoiceId: InvoiceId) {
                             val invoice = repository.findById(invoiceId)
                    +        provider.cancel(invoice.externalId)
                    +        invoice.markAsCancelled()
                    +        repository.save(invoice)
                    +        eventPublisher.publish(InvoiceCancelled(invoice.id))
                         }
                     }
                """.trimIndent()
            ),
            change(
                path = "src/main/kotlin/billing/integration/BillingProviderClient.kt",
                diff = """
                    @@ -20,6 +20,12 @@ class BillingProviderClient(
                    +    fun cancel(externalId: String): CancelResponse {
                    +        return httpClient.post("/v1/invoices/${'$'}externalId/cancel")
                    +    }
                     }
                """.trimIndent()
            )
        )
    )

    /** Cenário 2: código que parece problema mas o contexto invalida. */
    fun falsePositiveMr(): MergeRequest = mergeRequest(
        iid = 102,
        title = "Extrair validação de cupom",
        changes = listOf(
            change(
                path = "src/main/kotlin/orders/domain/CouponValidator.kt",
                diff = """
                    @@ -10,4 +10,10 @@ class CouponValidator {
                    +    fun validate(coupon: Coupon) {
                    +        require(coupon.isActive) { "cupom inativo" }
                    +    }
                     }
                """.trimIndent()
            )
        )
    )

    /** Cenário 4: mudança relevante de comportamento sem nenhum arquivo de teste. */
    fun missingTestsMr(): MergeRequest = mergeRequest(
        iid = 104,
        title = "Alterar cálculo de imposto",
        changes = listOf(
            change(
                path = "src/main/kotlin/tax/domain/TaxCalculator.kt",
                diff = buildString {
                    appendLine("@@ -1,5 +1,95 @@")
                    appendLine(" class TaxCalculator {")
                    (1..92).forEach { appendLine("+    fun rule$it(): Int = $it") }
                    appendLine(" }")
                }
            )
        )
    )

    /** Cenário 5: contrato divergente entre producer e consumer. */
    fun crossFileMr(): MergeRequest = mergeRequest(
        iid = 105,
        title = "Publicar evento de pagamento",
        changes = listOf(
            change(
                path = "src/main/kotlin/payments/messaging/PaymentEventProducer.kt",
                diff = """
                    @@ -12,6 +12,12 @@ class PaymentEventProducer(
                    +    fun publish(payment: Payment) {
                    +        kafkaTemplate.send("payments", PaymentEvent(id = payment.id, amountCents = payment.amountCents))
                    +    }
                     }
                """.trimIndent()
            ),
            change(
                path = "src/main/kotlin/ledger/messaging/PaymentEventConsumer.kt",
                diff = """
                    @@ -8,6 +8,12 @@ class PaymentEventConsumer(
                    +    @KafkaListener(topics = ["payments"])
                    +    fun onEvent(event: PaymentEvent) {
                    +        ledger.register(event.id, event.amount)
                    +    }
                     }
                """.trimIndent()
            )
        )
    )

    /** Cenário 6: MR correto, sem findings esperados. */
    fun cleanMr(): MergeRequest = mergeRequest(
        iid = 106,
        title = "Corrigir typo em mensagem de erro",
        changes = listOf(
            change(
                path = "src/main/kotlin/shared/Messages.kt",
                diff = """
                    @@ -3,3 +3,3 @@ object Messages {
                    -    const val INVALID = "Requisicao invalida"
                    +    const val INVALID = "Requisição inválida"
                     }
                """.trimIndent()
            )
        )
    )

    /** Cenário 7: risco plausível mas sem evidência — deve virar questionamento. */
    fun uncertainRiskMr(): MergeRequest = mergeRequest(
        iid = 107,
        title = "Chamar serviço de score no cadastro",
        changes = listOf(
            change(
                path = "src/main/kotlin/customers/application/RegisterCustomerUseCase.kt",
                diff = """
                    @@ -30,6 +30,11 @@ class RegisterCustomerUseCase(
                    +        val score = scoreClient.fetch(document)
                    +        customer.applyScore(score)
                    +        repository.save(customer)
                     }
                """.trimIndent()
            )
        )
    )

    /** MR com credencial literal adicionada, para a regra estática de segredos. */
    fun leakedSecretMr(): MergeRequest = mergeRequest(
        iid = 108,
        title = "Configurar integração",
        changes = listOf(
            change(
                path = "src/main/kotlin/config/IntegrationConfig.kt",
                diff = """
                    @@ -5,4 +5,8 @@ object IntegrationConfig {
                    +    const val apiKey = "sk-live-9f2b71ac44de8810bb3d"
                    +    val fallbackToken = System.getenv("FALLBACK_TOKEN")
                    +    val userPassword = credentials.password
                     }
                """.trimIndent()
            )
        )
    )
}
