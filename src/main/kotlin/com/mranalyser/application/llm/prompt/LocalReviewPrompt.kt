package com.mranalyser.application.llm.prompt

import com.mranalyser.application.port.LlmPurpose
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.review.ChunkReviewInput
import com.mranalyser.domain.model.ChangeGroup

/**
 * Etapa 2: deep review local, por chunk coeso (item 5).
 *
 * O prompt é adaptado ao grupo arquitetural do chunk: revisar uma migration com a mesma
 * checklist de um consumer de Kafka gera ruído. O foco por grupo está em [focusFor].
 */
class LocalReviewPrompt(
    private val sections: PromptSections = PromptSections()
) {
    fun build(input: ChunkReviewInput, maxOutputTokens: Int): LlmRequest = LlmRequest(
        purpose = LlmPurpose.LOCAL_REVIEW,
        system = ReviewPromptPolicy.systemPrompt(
            ReviewPromptPolicy.REASONING_CHAIN,
            ReviewPromptPolicy.FINDING_TYPE_TAXONOMY,
            ReviewPromptPolicy.SEVERITY_RUBRIC,
            ReviewPromptPolicy.CONFIDENCE_RUBRIC,
            ReviewPromptPolicy.EVIDENCE_REQUIREMENT,
            ReviewPromptPolicy.BLOCKING_RUBRIC,
            ReviewPromptPolicy.SCOPE_POLICY,
            ReviewPromptPolicy.COMMENT_STYLE,
            ReviewPromptPolicy.NOISE_POLICY
        ),
        user = buildString {
            appendLine("# TAREFA: DEEP REVIEW DO GRUPO ${input.group.name} (chunk ${input.chunkIndex}/${input.chunkCount})")
            appendLine()
            appendLine(DIFF_LEGEND)
            appendLine()
            appendLine(focusFor(input.group))
            appendLine()
            appendLine(ReviewPromptPolicy.DEEP_REVIEW_CHECKLIST)
            appendLine()
            appendLine(sections.mergeRequestHeader(input.overview))
            sections.understanding(input.understanding).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            sections.architecturalSignals(input.architecturalSignals).takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine(it)
            }
            appendLine()
            appendLine(sections.changedFilesTable(input.overview))
            appendLine()
            appendLine(sections.discussions(input.discussions))
            appendLine()
            appendLine(sections.relatedContext(input.relatedContext))
            appendLine()
            appendLine("## DIFF A REVISAR NESTE CHUNK")
            input.files.forEach { file ->
                appendLine()
                appendLine(sections.clean(file.annotatedDiff))
            }
            appendLine()
            appendLine(SCHEMA)
        },
        maxOutputTokens = maxOutputTokens,
        temperature = 0.1,
        label = "chunk ${input.chunkIndex}/${input.chunkCount} (${input.group.name})"
    )

    /**
     * Direciona a atenção conforme a camada. Reduz ruído e aumenta a chance de encontrar o
     * problema que realmente importa naquele tipo de arquivo.
     */
    fun focusFor(group: ChangeGroup): String {
        val focus = when (group) {
            ChangeGroup.DOMAIN -> """
- invariantes do domínio e estados impossíveis;
- regra de negócio aplicada na camada correta e sem duplicação;
- dependência de infraestrutura vazando para o domínio;
- regra dependente de ordem de execução.
""".trim()

            ChangeGroup.APPLICATION -> """
- orquestração: ordem entre efeito externo e persistência;
- fronteira transacional do caso de uso;
- propagação e tradução de erro;
- idempotência da operação quando ela pode ser reexecutada.
""".trim()

            ChangeGroup.PERSISTENCE -> """
- fronteira transacional e rollback; atualização parcial;
- read-modify-write sem lock otimista;
- N+1, query sem limite, operação em lote;
- compatibilidade com dados já existentes.
""".trim()

            ChangeGroup.INTEGRATION -> """
- timeout, retry, backoff, circuit breaker e fallback;
- idempotência e duplicidade em caso de retry;
- partial failure: o que acontece se esta chamada funcionar e a próxima falhar;
- chamada remota dentro de transação;
- tradução de erro do fornecedor para erro da aplicação.
""".trim()

            ChangeGroup.API -> """
- compatibilidade retroativa do contrato (campo removido, tipo alterado, obrigatoriedade);
- autenticação e autorização do novo caminho;
- validação de entrada e códigos de erro;
- exposição de dado sensível na resposta.
""".trim()

            ChangeGroup.MESSAGING -> """
- entrega pelo menos uma vez e idempotência do consumer;
- ordem de eventos e dependência entre mensagens;
- poison message, DLQ e comportamento no erro;
- concorrência entre múltiplas instâncias do consumer;
- compatibilidade do payload com produtores/consumidores existentes.
""".trim()

            ChangeGroup.MIGRATION -> """
- compatibilidade com dados existentes e com a versão anterior da aplicação em execução;
- coluna NOT NULL sem default em tabela populada;
- lock de tabela e tempo de execução em volume real;
- reversibilidade;
- consistência com a entidade e o repositório correspondentes.
""".trim()

            ChangeGroup.CONFIGURATION -> """
- alteração de timeout, retry, pool ou limite e seu efeito em produção;
- valor default perigoso;
- segredo em arquivo versionado;
- divergência entre ambientes.
""".trim()

            ChangeGroup.CONTRACT -> """
- quebra de compatibilidade para consumidores existentes;
- campo obrigatório novo;
- versionamento do contrato.
""".trim()

            ChangeGroup.TEST -> """
- o teste realmente verifica o comportamento novo ou apenas exercita o código?
- asserção ausente ou fraca; mock que esconde o comportamento sob teste;
- cenários faltantes: falha externa, estado inválido, duplicidade, concorrência, retry.
Não gere finding sobre estilo de teste.
""".trim()

            ChangeGroup.BUILD -> """
- nova dependência: necessidade, licença, tamanho, sobreposição com o que já existe;
- alteração de versão com impacto de comportamento;
- alteração de configuração de build que afeta runtime.
""".trim()

            ChangeGroup.DOCUMENTATION -> """
- apenas verifique se a documentação contradiz o código alterado neste MR.
Não gere finding de estilo de escrita.
""".trim()

            ChangeGroup.OTHER -> """
- avalie conforme o conteúdo real do arquivo, aplicando a checklist geral.
""".trim()
        }

        return "## FOCO PARA O GRUPO ${group.name} (${group.label})\n$focus"
    }

    private companion object {
        /** Item 30. */
        val DIFF_LEGEND = """
## COMO LER O DIFF
Cada linha vem no formato: `TAG   NUMERO | conteúdo`
- `ADD  123 | ...`   linha ADICIONADA. `123` é o número real da linha no arquivo novo.
- `DEL (456) | ...`  linha REMOVIDA. Esse código NÃO EXISTE MAIS. Nunca relate problema nele.
- `ctx  123 | ...`   linha de CONTEXTO, não alterada. Serve para entender o entorno.

Ao preencher "line", use o número mostrado na linha `ADD` ou `ctx` correspondente.
Foque nas linhas `ADD` e no efeito que elas produzem no fluxo mostrado pelas linhas `ctx`.
""".trim()

        val SCHEMA = """
## FORMATO DE RESPOSTA (JSON único)
{
  "summary": "1 a 2 frases sobre o que este conjunto de arquivos faz e o estado técnico dele",
  "findings": [
    {
      "type": "BUG|RISK|DESIGN|ARCHITECTURE|QUESTION|SUGGESTION",
      "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
      "category": "BUG|BUSINESS_RULE|SECURITY|ARCHITECTURE|DESIGN|PERFORMANCE|CONCURRENCY|TRANSACTION|DATA_CONSISTENCY|RELIABILITY|API_CONTRACT|OBSERVABILITY|OPERATIONS|TESTABILITY|MAINTAINABILITY|CODE_STYLE|COMPATIBILITY|DOCUMENTATION",
      "scope": "INTRODUCED|PRE_EXISTING",
      "file": "caminho/exato/do/arquivo",
      "line": 84,
      "title": "título curto e específico, sem jargão de auditoria",
      "description": "o que encontrei e por que é problema",
      "evidence": "fato verificável citando arquivo, linha e o que o código faz",
      "failureScenario": "1. ...\\n2. ...\\n3. ...\\n4. estado resultante",
      "impact": "consequência concreta",
      "recommendation": "o que deveria ser avaliado ou corrigido",
      "blocking": false,
      "commentType": "BLOCKER|QUESTION|SUGGESTION|OBSERVATION|PRAISE",
      "suggestedComment": "comentário em pt-BR no estilo de um revisor sênior",
      "componentsAffected": ["ComponenteA", "ComponenteB"],
      "relatedFiles": ["outro/arquivo/relacionado.kt"],
      "confidence": 0.85
    }
  ],
  "questions": ["pergunta ao autor que não caiba como finding"],
  "positivePoints": ["apenas pontos tecnicamente relevantes: redução de complexidade, melhor boundary, tratamento correto de falha, cobertura de caso extremo, melhora de observabilidade, desacoplamento relevante"],
  "suggestedRecommendation": "APPROVE|APPROVE_WITH_SUGGESTIONS|NEEDS_DISCUSSION|REQUEST_CHANGES"
}

Regras finais:
- `"findings": []` é uma resposta válida e desejável quando não há problema material.
- Não gere elogio trivial em "positivePoints" ("código limpo", "bem escrito", "boa nomenclatura").
- Um finding por problema. Não repita o mesmo problema em arquivos diferentes.
""".trim()
    }
}
