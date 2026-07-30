# mr-analyser — Engine de Review em nível de Especialista de Tecnologia

Documento de decisão arquitetural. Descreve o diagnóstico da implementação anterior,
as limitações encontradas, a arquitetura adotada e o que foi deliberadamente **não**
implementado da especificação, com justificativa.

---

## 1. Diagnóstico da implementação anterior

A V1 era um pipeline linear correto no esqueleto (Clean/Hexagonal bem separado, portas
explícitas, CLI desacoplada) mas com quatro problemas estruturais que limitavam o teto de
qualidade da análise, independentemente do modelo usado.

### 1.1 A inteligência de review estava dentro do provider (bloqueador principal)

`LlmProvider.analyse(ReviewContext): LlmReviewResult` obrigava cada provider a possuir
`PromptBuilder` + `LlmResponseParser`. Consequências:

- prompt e parsing duplicados em 4 providers;
- **impossível executar mais de uma etapa de raciocínio**: não havia como pedir ao modelo
  "valide este finding" ou "correlacione estes arquivos", porque o único prompt existente
  estava compilado dentro do transporte HTTP;
- violava o item 35 da especificação (provider deve cuidar apenas de comunicação).

Esse era o gargalo real. Nenhuma melhoria de prompt resolveria isso.

### 1.2 Perda de visão global

`MergeRequestAnalyzer` disparava N chamadas independentes (uma por chunk) e concatenava:

```kotlin
llmResults.mapNotNull { it.summary }.joinToString("\n")
```

Resultado: N resumos colados, sem correlação entre arquivos, sem detecção de problema
cross-file, sem ajuste de severidade global e sem parecer único.

### 1.3 Localização de findings incorreta

As regras estáticas usavam o índice da linha **dentro do texto do diff** como número de
linha do arquivo:

```kotlin
change.diff.lineSequence().forEachIndexed { index, line ->
    if (line.startsWith("+") && pattern.containsMatchIn(line)) { ... line = index + 1 ... }
```

Ou seja: todo `file:line` reportado pelas regras estava errado. E o LLM recebia diff bruto,
sem âncora de linha, portanto também tinha que adivinhar `line`.

### 1.4 Ausência de etapa de validação

Todo finding retornado pelo modelo entrava no relatório, filtrado apenas por
`confidence >= minimumConfidence` e por heurísticas textuais em português
(`FindingDeduplicator.isLikelyFalsePositive`). Não havia nenhuma verificação de
"existe evidência concreta?" — exatamente o passo que separa 4 findings excelentes de
20 superficiais.

---

## 2. Limitações encontradas (lista completa)

Além dos quatro itens acima:

| # | Problema | Impacto |
|---|---|---|
| L1 | `GitLabChangeDto` usava `newFile`/`deletedFile`/`renamedFile` sem `@SerialName`. O GitLab envia `new_file`/`deleted_file`/`renamed_file`. Com `ignoreUnknownKeys=true` os campos ficavam **sempre `false`** | arquivos deletados nunca eram identificados; `added`/`renamed` inúteis; `ReviewChunker.filter { !it.deleted }` nunca filtrava nada |
| L2 | `LocalRepositoryContextProvider` varria o **diretório de trabalho atual**, que é o checkout do próprio `mr-analyser`, não o repositório do MR | contexto "relacionado" totalmente alheio ao MR era injetado no prompt — fonte ativa de falso positivo |
| L3 | O filtro `domainRelated` casava qualquer arquivo cujo nome contivesse `test/service/repository/controller/handler/usecase`, **independentemente do arquivo alterado** | 4 arquivos arbitrários por arquivo alterado |
| L4 | `root.walkTopDown().toList()` materializava o repositório inteiro (só excluía `.git`, `build`, `.gradle`) | custo alto, binários, `node_modules`, `.venv` |
| L5 | `.take(4)` aplicado sem ranking, em ordem de caminhada | seleção arbitrária |
| L6 | Excerto = primeiras 120 linhas do arquivo | truncava no meio da classe, raramente incluía o símbolo relevante |
| L7 | `AnthropicLlmProvider` e `GeminiLlmProvider` sem `runCatching` e sem `HttpTimeout` | um chunk com erro derrubava `awaitAll` e abortava a análise inteira; timeout default infinito |
| L8 | `maxTokens = 2048` | truncava a resposta em MRs médios |
| L9 | `MergeRecommendationCalculator`: 1 HIGH → `APPROVE_WITH_SUGGESTIONS`, 2 HIGH → `REQUEST_CHANGES` | critério puramente por contagem de severidade; um único HIGH com cenário de falha concreto não bloqueava |
| L10 | `PromptBuilder.sanitize` redigia qualquer `x = y` cujo identificador contivesse `token/secret/password/key` | `val apiKey = config.apiKey` virava `val apiKey =<REDACTED>`, destruindo contexto legítimo de review |
| L11 | `existingDiscussions` era achatado em `List<String>` de corpos de nota, incluindo notas de sistema, sem `resolved` nem `position` | não era possível saber se a discussão já estava resolvida nem a que arquivo/linha se referia |
| L12 | `Commit.message` vinha de `title` (GitLab) | corpo do commit, onde geralmente está a intenção, era descartado |
| L13 | `SecretsRule` disparava CRITICAL em qualquer `password =`, inclusive referências (`= user.password`) | falso positivo CRITICAL, o pior tipo |
| L14 | `FindingDeduplicator` usava Levenshtein sobre descrições completas para julgar duplicidade **e** falso positivo | O(n²·m²) e semanticamente inadequado para detecção de FP |
| L15 | Chunking apenas por tamanho | arquivos de domínio, persistência e integração misturados no mesmo prompt |
| L16 | `ReviewFinding` sem `evidence`, `failureScenario`, `blocking`, `type`, `commentType`, escopo | impossível verificar rapidamente por que o finding existe |

---

## 3. Arquitetura adotada

Mantidos: Kotlin, JVM 21, Gradle, Clean/Hexagonal, `LlmProvider`, CLI.

### 3.1 Decisão central: inverter a responsabilidade do provider

```
ANTES                                  DEPOIS
LlmProvider.analyse(ReviewContext)     LlmProvider.complete(LlmRequest): LlmResponse
  └─ PromptBuilder    (infra)            └─ apenas HTTP + mapeamento de erro
  └─ LlmResponseParser(infra)
                                       application/llm/
                                         prompt/*  (5 prompts especializados)
                                         parser/*  (parsing robusto)
```

`LlmRequest(system, user, maxOutputTokens, temperature, purpose)` /
`LlmResponse(text, failure)`. O provider **nunca** falha com exceção: erro vira
`LlmResponse(failure = ...)`. Isso é o que permite análise parcial (item 38).

**Onde ficam prompt e parsing?** Em `application/llm`, não em `infrastructure`. Justificativa:
prompt e schema de resposta *são* a política de análise — o contrato semântico com o modelo —
e precisam ser reutilizados por todos os providers. Colocá-los na infraestrutura os
acoplaria ao transporte, que é exatamente o defeito que estamos corrigindo. O que permanece
em `infrastructure/llm` é só HTTP, autenticação, timeout e retry.

### 3.2 Pipeline

```
AnalyseMergeRequestUseCase
  └─ MergeRequestAnalyzer            (orquestrador fino, ~150 linhas, zero heurística)
       ├─ 0  ChangeClassifier              → ChangeGroup por arquivo
       ├─ 0  ArchitecturalSignalDetector   → sinais determinísticos (item 29)
       ├─ 0  RepositoryContextRetriever    → context retrieval com gate de identidade
       ├─ 1  ChangeUnderstandingStage  (LLM)  → "Entendimento da alteração" + blast radius
       ├─ 2  ReviewChunker                 → chunks coesos por grupo, diff anotado
       ├─ 3  StaticRuleStage               → regras determinísticas (linha correta)
       ├─ 3  LocalReviewStage      (LLM, paralelo) → findings candidatos
       ├─ 4  FindingDeduplicator           → dedup + já-discutido
       ├─ 5  FindingValidationStage (LLM)  → KEEP / QUESTION / DISCARD (item 8)
       ├─ 6  CrossFileReviewStage   (LLM)  → findings cross-file + ajuste global
       ├─ 7  FindingRefinementPolicy       → evidência obrigatória, blocking, noise
       ├─ 8  MergeRecommendationCalculator → APPROVE | …_SUGGESTIONS | NEEDS_DISCUSSION | REQUEST_CHANGES
       └─ 9  FinalAssessmentStage   (LLM)  → parecer técnico + risco principal
```

Cada etapa é uma classe própria com uma responsabilidade, testável isoladamente.
`MergeRequestAnalyzer` apenas sequencia e agrega diagnósticos — não contém regra de decisão.

Todas as etapas LLM são **degradáveis**: se falharem, a análise continua e o relatório é
marcado como parcial, com o motivo registrado em `AnalysisQuality.warnings`.

### 3.3 Autoridade da decisão: determinística, não do modelo

O modelo *sugere*; `FindingRefinementPolicy` + `MergeRecommendationCalculator` *decidem*.

- `blocking` **não** é inferido só da severidade (item 13). É função de
  severidade × tipo × confiança × existência de cenário de falha × escopo.
- Finding `MEDIUM+` sem evidência é rebaixado a `QUESTION` com severidade limitada
  (item 9) em vez de ser apresentado como acusação.
- Se o modelo recomenda `REQUEST_CHANGES` mas nenhum finding é bloqueante, o resultado é
  `NEEDS_DISCUSSION` — não se perde o sinal nem se bloqueia sem evidência.

### 3.4 Context retrieval

`SymbolExtractor` extrai do diff: imports, símbolos declarados, supertipos e tipos
referenciados. `LocalRepositoryContextProvider` indexa o repositório uma vez (com poda de
diretórios e filtro de extensão/tamanho) e **rankeia** candidatos na ordem do item 34:

```
símbolo alterado → interface/supertipo → caller → dependência direta → teste → configuração/migration
```

`SourceExcerptExtractor` devolve um recorte útil (cabeçalho + declarações + janelas ao redor
dos símbolos-alvo), não as primeiras 120 linhas.

**Gate de identidade de repositório:** o retrieval só roda se o `origin` do diretório atual
corresponder ao projeto do MR. Caso contrário devolve vazio e registra um aviso. Isso elimina
a maior fonte de falso positivo da V1 (L2).

### 3.5 Diff consciente de ADDED/REMOVED/CONTEXT

`UnifiedDiffParser` produz `DiffLine(origin, content, oldLine, newLine)` a partir dos
cabeçalhos `@@`. Usado para:

- números de linha corretos nas regras estáticas;
- `AnnotatedDiffRenderer`, que envia ao modelo linhas prefixadas com `ADD/DEL/ctx` e o número
  de linha real (item 30) — o modelo deixa de comentar código removido como se existisse.

---

## 4. O que foi deliberadamente não implementado

| Item da spec | Decisão | Justificativa |
|---|---|---|
| Novas regras estáticas heurísticas (ex.: "chamada remota dentro de transação" por regex) | não implementado | exige raciocínio de fluxo entre linhas; por regex geraria falso positivo — justamente o que a spec pede para minimizar. Delegado às etapas LLM, que têm o contexto. |
| `reasoning` como campo separado de `evidence` (item 9) | implementado como campo, mas os prompts pedem `evidence` + `failureScenario` como obrigatórios e `reasoning` como opcional | três campos livres redundantes incentivam o modelo a repetir texto. `evidence` (fato verificável) + `failureScenario` (sequência) já respondem "por que o finding existe". |
| Publicação automática de comentários no GitLab | fora de escopo | a CLI atual é read-only; a spec pede sugestão de comentário, não publicação. |
| RAG / embeddings para context retrieval | não implementado | retrieval por símbolo/estrutura resolve o caso real (arquivos do mesmo repositório) sem introduzir infraestrutura de índice vetorial. Deixado como evolução. |
| `jsonMode` (response_format) ligado por padrão | default `false`, configurável | muitos gateways "OpenAI-compatible" e proxies de Ollama retornam 400 para `response_format`. Um 400 é falha permanente; o parser robusto cobre o caso sem esse risco. |

---

## 5. Formato do relatório

Ordem final (console e markdown):

```
cabeçalho do MR + estatísticas
ENTENDIMENTO DA ALTERAÇÃO           ← item 4
MUDANÇAS ESTRUTURAIS DETECTADAS     ← item 29 (só se houver)
PONTOS QUE EU REVISARIA NO MR       ← item 22, produto principal
  🔴 Solicitaria ajuste  (blocking)
  🟡 Questionaria        (QUESTION / RISK sem prova)
  🔵 Sugestões           (SUGGESTION / LOW)
  ✅ Pontos tecnicamente adequados
DÍVIDA TÉCNICA NÃO INTRODUZIDA POR ESTE MR   ← item 19 (só se houver)
PARECER TÉCNICO                     ← item 21
PARECER (bloco final)               ← item 23
QUALIDADE DA ANÁLISE                ← item 33
```

Categorias vazias não são impressas.

---

## 6. Critério de aceite

A pergunta de validação é a do item 41: após ler o relatório, o revisor sabe exatamente o que
comentar. Os mecanismos que sustentam isso são, em ordem de impacto:

1. etapa de validação que descarta finding sem evidência;
2. `blocking` derivado de cenário de falha, não de severidade;
3. contexto relacionado real (com gate de identidade) reduzindo FP por análise isolada;
4. diff anotado com origem da linha;
5. comentários sugeridos em linguagem de revisor, classificados por tipo.
