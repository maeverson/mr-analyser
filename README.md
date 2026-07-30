# mr-analyser

Revisão assistida de Merge Requests do GitLab, em nível de Especialista de Tecnologia.

A ferramenta não tenta substituir o julgamento do revisor. Ela responde uma pergunta:

> **Quais pontos eu deveria comentar, questionar ou solicitar ajuste neste MR?**

A prioridade absoluta é **signal-to-noise ratio**. Vale mais 4 findings excelentes que 20
superficiais, e uma lista vazia é uma resposta legítima.

## Princípios de julgamento

O sistema distingue seis naturezas de achado, porque tratar dúvida como acusação é a principal
forma de destruir a confiança em um relatório automatizado:

| Tipo | Significado |
|---|---|
| `BUG` | há evidência concreta de comportamento incorreto |
| `RISK` | pode funcionar, mas existe condição relevante capaz de provocar falha |
| `DESIGN` | funciona, porém introduz dívida técnica |
| `ARCHITECTURE` | impacto estrutural, acoplamento ou responsabilidade incorreta |
| `QUESTION` | falta informação para afirmar problema; merece esclarecimento do autor |
| `SUGGESTION` | melhoria possível, não bloqueia aprovação |

Três regras estruturais decorrem disso:

1. **Evidência é obrigatória** a partir de `MEDIUM`. Sem evidência, o achado é rebaixado a
   questionamento em vez de ser apresentado como fato.
2. **Bloqueio não deriva de severidade.** `HIGH` sem cenário de falha não bloqueia; `MEDIUM` com
   corrupção garantida em cenário específico bloqueia.
3. **A decisão é determinística.** O modelo sugere; as políticas de domínio decidem. Um finding
   que não passou pela validação nunca segura um merge.

## Pipeline

```
GitLab MR
   │
   ├── ChangeClassifier            → grupo arquitetural por arquivo
   ├── UnifiedDiffParser           → linhas com ADDED/REMOVED/CONTEXT e número real
   ├── ArchitecturalSignalDetector → nova dependência, migration, endpoint, timeout, retry…
   ├── SymbolExtractor + RepositoryContextRetriever → contexto relacionado (com gate de identidade)
   │
   ├── 1. Entendimento da alteração        (LLM)  intenção, contratos, blast radius
   ├── 2. Deep review por chunk coeso      (LLM)  findings candidatos, prompt por camada
   ├── 3. Regras estáticas                        segredo, debug, TODO, tamanho, testes
   ├── 4. Deduplicação                            duplicatas e pontos já discutidos
   ├── 5. Validação adversarial            (LLM)  KEEP / QUESTION / DISCARD  ← maior efeito no ruído
   ├── 6. Análise cross-file               (LLM)  problemas visíveis só entre arquivos
   ├── 7. Políticas: evidência, bloqueio, ruído
   ├── 8. MergeRecommendationCalculator
   └── 9. Parecer técnico                  (LLM)
```

Nenhuma etapa LLM é obrigatória. Se uma falhar, a análise continua degradada e o motivo aparece
na seção "Qualidade da análise" do relatório.

## Estrutura do relatório

```
ENTENDIMENTO DA ALTERAÇÃO
MUDANÇAS ESTRUTURAIS DETECTADAS
PONTOS QUE EU REVISARIA NO MR
  🔴 Solicitaria ajuste       (bloqueantes: evidência + cenário de falha)
  🟡 Questionaria             (risco legítimo sem prova suficiente)
  🔵 Sugestões                (não bloqueiam)
  ✅ Pontos tecnicamente adequados
DÍVIDA TÉCNICA NÃO INTRODUZIDA POR ESTE MR
PARECER TÉCNICO
PARECER          → APPROVE | APPROVE_WITH_SUGGESTIONS | NEEDS_DISCUSSION | REQUEST_CHANGES
QUALIDADE DA ANÁLISE
```

Categorias vazias não são impressas.

## Stack

Kotlin · JVM 21 · Gradle Kotlin DSL · Ktor Client · kotlinx.serialization · Clikt ·
SLF4J + Logback · JUnit 5 · MockK · WireMock

## Build

```bash
./gradlew clean build
```

## Configuração

Origem, em ordem de precedência:

1. flags da CLI (`--provider`, `--model`, `--fast`, `--no-context`, …)
2. `.mranalyser.properties`
3. variáveis de ambiente
4. `.mranalyser.yml`
5. defaults internos

### `.mranalyser.properties` (segredos)

```properties
GITLAB_URL=https://gitlab.com
GITLAB_TOKEN=<token com permissão de leitura de MR>

MR_ANALYSER_LLM_PROVIDER=openai
MR_ANALYSER_LLM_MODEL=gpt-4o-mini
MR_ANALYSER_LLM_API_KEY=<chave>
MR_ANALYSER_LLM_URL=https://api.openai.com/v1

MR_ANALYSER_MAX_CONCURRENCY=4
```

### `.mranalyser.yml` (política de análise)

Copie de `.mranalyser.yml.example`, que documenta todos os campos e defaults.

Chaves equivalentes por variável de ambiente: `MR_ANALYSER_LLM_TIMEOUT_SECONDS`,
`MR_ANALYSER_LLM_MAX_RETRIES`, `MR_ANALYSER_LLM_JSON_MODE`, `MR_ANALYSER_LLM_MAX_TOKENS`,
`MR_ANALYSER_MIN_CONFIDENCE`, `MR_ANALYSER_MAX_FINDINGS`, `MR_ANALYSER_STAGE_UNDERSTANDING`,
`MR_ANALYSER_STAGE_VALIDATION`, `MR_ANALYSER_STAGE_CROSS_FILE`, `MR_ANALYSER_STAGE_ASSESSMENT`,
`MR_ANALYSER_CONTEXT_ENABLED`, `MR_ANALYSER_CONTEXT_REQUIRE_REPO_MATCH`,
`MR_ANALYSER_CONTEXT_MAX_FILES_PER_CHANGE`, `MR_ANALYSER_CONTEXT_MAX_TOTAL_FILES`,
`MR_ANALYSER_CONTEXT_MAX_CHARS`, `MR_ANALYSER_MAX_DIFF_LINES`, `MR_ANALYSER_MAX_FILE_LINES`.

`mr-analyser config show` imprime a configuração efetiva com segredos mascarados.

## Uso

```bash
# por URL
./gradlew run --args="analyse --url https://gitlab.com/grupo/projeto/-/merge_requests/123"

# por projeto e IID
./gradlew run --args="analyse --project grupo/projeto --mr 123"

# dentro do repositório do MR, com autodiscovery pelo origin
./gradlew run --args="analyse --mr 123"
```

Opções: `--project`, `--mr`, `--url`, `--provider`, `--model`,
`--output console|markdown|json|gitlab-comments`, `--verbose`, `--show-low-confidence`,
`--no-context`, `--fast`.

O relatório é impresso e salvo em `reports/`.

### Contexto do repositório local

O retrieval só é usado se o `origin` do diretório atual corresponder ao projeto do MR. Rodar
de fora do repositório correto **não** injeta contexto alheio: a etapa é registrada como não
executada. Para análise com contexto, execute de dentro do checkout do projeto do MR.

Sem contexto, os prompts instruem o modelo a **não** concluir ausência de retry, timeout,
transação, validação ou idempotência a partir do diff — nesses casos ele deve perguntar.

### Formatos de saída

| Formato | Uso |
|---|---|
| `console` | leitura durante a revisão (default) |
| `markdown` | colar em wiki, issue ou descrição de MR |
| `json` | automação e auditoria — inclui evidência, cenário de falha, veredito da validação e origem de cada finding |
| `gitlab-comments` | comentários prontos, classificados em `BLOCKER`/`QUESTION`/`SUGGESTION`/`OBSERVATION`/`PRAISE` |

## Arquitetura

Clean/Hexagonal:

| Camada | Conteúdo |
|---|---|
| `domain` | modelos, parser de diff, políticas de decisão, regras estáticas, redação de segredos |
| `application` | pipeline (`review/`), prompts e parsing (`llm/`), portas (`port/`), serviços (`service/`) |
| `infrastructure` | GitLab, providers de LLM (só transporte), config, renderizadores, contexto local |
| `cli` | entrada da aplicação |

**Decisão central:** `LlmProvider` expõe `complete(LlmRequest): LlmResponse` — texto entra, texto
sai. Prompt, schema de resposta e parsing vivem em `application/llm` e são compartilhados por
todos os providers. Sem essa inversão só existiria um prompt possível e, portanto, uma única
etapa de análise. O provider nunca lança exceção: falha vira `LlmResponse.failed`.

Providers: `openai`, `anthropic`, `gemini`, `ollama`, e `noop` quando não há chave configurada.

Detalhes do diagnóstico da versão anterior e das decisões tomadas (incluindo o que foi
deliberadamente **não** implementado): [docs/ARCHITECTURE-REVIEW-ENGINE.md](docs/ARCHITECTURE-REVIEW-ENGINE.md).

## Segurança

- tokens nunca são impressos em log; `config show` mascara segredos;
- credenciais literais são mascaradas antes do envio ao modelo — referências a símbolo
  (`config.apiKey`, `System.getenv(...)`) são preservadas, porque são contexto legítimo de review;
- todo conteúdo do repositório é tratado como dado não confiável nos prompts, com instrução
  explícita contra prompt injection em commit, descrição, código e discussões;
- `ignoredPaths` e `ignoredCategories` limitam o que é enviado e o que é reportado.

## Testes

```bash
./gradlew clean test
```

Cobertura: parsing tolerante de resposta (JSON cercado, prosa, `<think>`, vírgula sobrando, tipo
errado, enum desconhecido), políticas de evidência/bloqueio/ruído, recomendação de merge,
deduplicação, classificação de mudança, sinais arquiteturais, parser de diff, regras estáticas,
context retrieval e gate de identidade, prompts das cinco etapas, providers de LLM sob falha
HTTP e indisponibilidade, retry, renderizadores e análise parcial.

Fixtures de MR em `src/test/kotlin/com/mranalyser/support/MergeRequestFixtures.kt`: bug real,
falso positivo, problema transacional, ausência de testes, problema cross-file, MR correto,
risco que deve virar questionamento e credencial vazada.

## Limitações conhecidas

- context retrieval é heurístico por símbolo e estrutura, não semântico (sem embeddings);
- a ferramenta é read-only: não publica comentários no GitLab;
- diffs muito grandes são truncados por arquivo — o truncamento é informado ao modelo e ao
  revisor, e o arquivo é sinalizado como analisado parcialmente.
