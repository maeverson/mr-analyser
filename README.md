# mr-analyser

AI-assisted GitLab Merge Request reviewer.

`mr-analyser` e uma ferramenta CLI para revisao assistida de Merge Requests do GitLab. O foco da V1 e apoiar o reviewer com sinais de risco relevantes, sem alterar codigo automaticamente.

## Principios

- simplicidade
- baixo acoplamento
- alta coesao
- testabilidade
- extensibilidade
- separacao entre dominio e infraestrutura

## Stack

- Kotlin
- JVM 21
- Gradle Kotlin DSL
- Ktor Client
- kotlinx.serialization
- Clikt
- SLF4J + Logback
- JUnit 5
- MockK
- WireMock

## Installation

### 1. Pre-requisitos

- Linux/macOS/Windows
- acesso a internet para baixar dependencias Gradle
- token do GitLab com permissao de leitura de MR
- chave de API de LLM (quando usar provider `openai`)

### 2. Build

```bash
./gradlew clean build --no-daemon
```

## Configuration

As configuracoes podem vir de:

- `.mranalyser.properties` (principal)
- `.mranalyser.yml` (regras/limites)
- variaveis de ambiente (fallback)

### Arquivo de propriedades do projeto

Copie o arquivo de exemplo e preencha os valores:

```bash
cp .mranalyser.properties.example .mranalyser.properties
```

Exemplo:

```properties
GITLAB_URL=https://gitlab.com
GITLAB_TOKEN=<token>

MR_ANALYSER_LLM_PROVIDER=openai
MR_ANALYSER_LLM_MODEL=gpt-4o-mini
MR_ANALYSER_LLM_API_KEY=<token>
MR_ANALYSER_LLM_URL=https://api.openai.com/v1

MR_ANALYSER_MAX_CONCURRENCY=4
```

Precedencia de configuracao:

1. flags da CLI (quando aplicavel, ex.: `--provider`, `--model`)
2. `.mranalyser.properties`
3. variaveis de ambiente
4. `.mranalyser.yml`
5. defaults internos

### Arquivo `.mranalyser.yml`

```yaml
review:
  ignoredPaths:
    - "*.lock"
    - "generated/**"
    - "vendor/**"

  ignoredCategories:
    - "CODE_STYLE"

  minimumConfidence: 0.60

limits:
  maxDiffLines: 5000
  maxFileLines: 1500

llm:
  provider: openai
  model: gpt-4o-mini
```

## GitLab token

- configure `GITLAB_TOKEN` no `.mranalyser.properties`
- o token nunca e impresso em logs
- `mr-analyser config show` mascara segredos

## LLM configuration

Provedores suportados na V1:

- `openai`
- `ollama`
- `anthropic`
- `gemini`
- fallback `noop` quando API key nao estiver configurada

Arquitetura preparada para evolucao:

- OpenAI
- Anthropic
- Ollama
- Gemini

### Self-hosted (Ollama)

Para rodar apontando para ambiente local/self-hosted, use `ollama`:

```properties
MR_ANALYSER_LLM_PROVIDER=ollama
MR_ANALYSER_LLM_MODEL=qwen2.5-coder:14b
MR_ANALYSER_LLM_URL=http://ollama.letsflowtech.com.br
```

Se seu Ollama estiver atras de proxy com autenticacao, configure tambem:

```properties
MR_ANALYSER_LLM_API_KEY=<token-opcional>
```

Observacao:

- Se `MR_ANALYSER_LLM_URL` terminar com `/api`, o cliente usa `.../api/generate`.
- Se nao terminar com `/api`, o cliente adiciona `/api/generate` automaticamente.

## Usage

### Analyse

Com projeto explicito:

```bash
./gradlew run --args="analyse --project group/project --mr 123"
```

Via URL:

```bash
./gradlew run --args="analyse --url https://gitlab.com/group/project/-/merge_requests/123"
```

Com autodiscovery local (dentro de repo Git com `origin` apontando para GitLab):

```bash
./gradlew run --args="analyse --mr 123"
```

Opcoes:

- `--project`
- `--mr`
- `--url`
- `--provider`
- `--model`
- `--output` (`console|markdown|json|gitlab-comments`)
- `--verbose`
- `--show-low-confidence`

### Config

```bash
./gradlew run --args="config show"
```

### Version

```bash
./gradlew run --args="version"
```

## Output

O relatorio em console inclui:

- resumo do MR
- findings por severidade
- perguntas para autor
- pontos positivos
- recomendacao final de merge

## Architecture

Arquitetura em estilo Clean/Hexagonal:

- `domain`: modelos e regras
- `application`: use cases, services e ports
- `infrastructure`: GitLab client/provider, LLM providers, config, renderer
- `cli`: entrada da aplicacao

Fluxo principal:

1. CLI resolve alvo (`--project/--mr` ou `--url` ou autodiscovery)
2. Provider GitLab coleta MR + changes + commits + discussions + approvals
3. `MergeRequestAnalyzer` executa:
   - regras estaticas
   - chunking de diff
   - analise LLM por chunk com limite de concorrencia
   - deduplicacao de findings
   - recomendacao de merge
4. `ConsoleReportRenderer` imprime resultado

## Security

- nao loga tokens
- prompt envia conteudo com mascaramento basico de segredos detectados
- considera conteudo do repositorio como dado nao confiavel (protege contra prompt injection)
- suporte a `ignoredPaths` e `ignoredCategories`

## Testes

Rodar todos os testes:

```bash
./gradlew clean test --no-daemon
```

Cobertura inicial inclui:

- `GitLabClient`
- `GitLabMergeRequestProvider`
- `MergeRequestAnalyzer`
- `ReviewChunker`
- `FindingDeduplicator`
- `MergeRecommendationCalculator`
- `ConsoleReportRenderer`

Fixtures:

- `src/test/resources/gitlab/`

## Roadmap

### V1

- CLI
- integracao GitLab
- analise de diff
- regras estaticas
- analise LLM
- relatorio console

### V2

- renderer Markdown e JSON
- contexto local de repositorio enriquecido
- regras mais especificas por linguagem

### V3

- publicacao de comentarios no GitLab
- modo interativo de review

### V4

- integracao com GitLab CI

### V5

- repository knowledge
- RAG
- regras arquiteturais organizacionais

## Limites atuais

- parser de YAML e simples (foco MVP)
- deduplicacao textual inicial
- provider LLM implementado apenas para OpenAI
- context discovery local ainda basico

## Licenca

Definir conforme necessidade do time.
