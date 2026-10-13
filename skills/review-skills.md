# Skills de revisão — stack Contabilizei (JVM / Spring / GCP)

Conhecimento do time injetado nos prompts de review local, validação e cross-file.
Pensado para modelos self-hosted (Ollama, 7B–14B), que não conhecem as convenções do
stack e erram em duas direções: reportam como problema o que o padrão já resolve e deixam
passar o desvio do padrão.

Formato de cada skill: frontmatter YAML entre linhas `---` e o corpo logo abaixo.
Este texto introdutório não é enviado ao modelo.

| Campo | Efeito |
|---|---|
| `name` | obrigatório; título da skill no prompt |
| `description` | uma linha exibida ao lado do título |
| `groups` | grupos do `ChangeClassifier` (DOMAIN, APPLICATION, PERSISTENCE, INTEGRATION, API, MESSAGING, MIGRATION, CONFIGURATION, CONTRACT, TEST, BUILD, DOCUMENTATION, OTHER) |
| `paths` | globs de caminho (`**/*.kt`, `src/main/**`) |
| `triggers` | trechos literais procurados no diff/finding (case-insensitive) |
| `stages` | `local-review`, `validation`, `cross-file-review` (default: as três) |
| `priority` | maior entra primeiro quando o orçamento (`MR_ANALYSER_SKILLS_MAX_CHARS`) aperta |

Todas as condições declaradas precisam casar. Não use `---` como separador dentro do corpo.
Cada prompt recebe só as skills aplicáveis aos arquivos dele; mantenha cada corpo curto
(até ~900 caracteres) — o que entra aqui sai do `num_ctx` disponível para o diff.

---
name: falsos-positivos-conhecidos
description: descarte estes candidatos na validação
stages: [validation]
priority: 100
---
DISCARD (ou DOWNGRADE_TO_QUESTION) quando o candidato:
- afirma "falta timeout/retry" em chamada RestClient/WebClient e o contexto não mostra a configuração do client — ela é centralizada em bean de configuração; no máximo pergunte;
- pede `@Transactional` em adapter/repositório chamado por caso de uso já transacional, ou onde há `TransactionTemplate`;
- aponta null check ausente em tipo Kotlin não-nulo (`String`, não `String?`) — o compilador garante;
- pede publicar evento "logo após o save" — no stack o evento sai pelo outbox, não direto;
- diz "não há testes" quando a tabela de arquivos alterados contém o teste correspondente;
- critica nome, formatação, `val`/`var`, ordem de imports ou uso de `data class` em DTO;
- se baseia em linha `DEL` ou em trecho truncado.

---
name: transacao-spring
description: fronteira transacional com Spring
groups: [APPLICATION, PERSISTENCE]
triggers: ["@Transactional", "TransactionTemplate", "transaction", ".save("]
priority: 60
---
Procure:
- `@Transactional` em método `private`/`final` (Kotlin sem plugin `allopen`/`spring`) ou chamado pela própria classe (self-invocation): o proxy não intercepta e NÃO há transação;
- chamada HTTP/gateway de pagamento dentro da transação: segura conexão do pool e não é desfeita no rollback;
- em Java, exceção checked não faz rollback sem `rollbackFor`;
- `REQUIRES_NEW` aninhado gravando dado que a transação externa pode desfazer;
- read-modify-write de saldo/status sem `@Version` nem lock (`SELECT ... FOR UPDATE`, `pg_advisory_xact_lock`).
Para afirmar ausência de transação, mostre a classe e o método que provam isso.

---
name: outbox-dual-write
description: eventos de domínio saem pelo Transactional Outbox
groups: [APPLICATION, MESSAGING, PERSISTENCE]
triggers: ["publish", "PubSubTemplate", "Outbox", "send(", "Publisher"]
priority: 70
---
Padrão do stack: mutação da entidade + gravação no `EventOutboxRepository` na MESMA transação; um dispatcher publica no Pub/Sub depois do commit.
É BUG (dual-write) quando o código novo:
- publica direto (`pubSubTemplate.publish`, `kafkaTemplate.send`, publisher do port) depois de `repository.save()` no mesmo fluxo — falha entre os dois perde o evento ou publica sem commit;
- grava no outbox fora da transação da mutação.
Não reporte a publicação feita pelo próprio dispatcher do outbox.
Payload novo/alterado: tópico é versionado (`...-v1`); campo removido ou renomeado quebra consumidores — crie `-v2` ou mantenha compatibilidade.

---
name: consumidor-pubsub
description: entrega at-least-once no Google Cloud Pub/Sub
groups: [MESSAGING]
priority: 65
---
Toda mensagem pode chegar duplicada e fora de ordem (sem ordering key). O consumer novo precisa:
- barreira de idempotência (chave `consumerKey` + `messageKey`, ou registro em `InboundEventRecorder`) ANTES do efeito colateral;
- lock transacional (`pg_advisory_xact_lock`) quando duas entregas simultâneas podem processar o mesmo agregado;
- `ack` só depois do commit; `ack` antes de processar perde a mensagem em falha;
- distinguir erro transitório (`nack`, redelivery) de mensagem inválida (ack + log/DLQ) — exceção genérica em payload inválido vira poison message em loop.
Pergunte em vez de afirmar quando a idempotência pode estar no adapter não mostrado.

---
name: valores-monetarios
description: dinheiro, escala e conversão em centavos
triggers: ["BigDecimal", "amount", "valor", "price", "preco", "preço", "cents", "centavos", "Double", "Float", "Money"]
priority: 80
---
- `Double`/`Float` para valor monetário é BUG (erro de representação).
- `BigDecimal`: escala 2 com `RoundingMode` explícito em `divide`/`setScale`; comparar com `compareTo`, nunca `equals` (`1.0` != `1.00`).
- Gateway de pagamento (Stripe) usa centavos inteiros (`Long`); domínio contábil usa decimal. Conversão manual (`* 100`, `/ 100`) fora do conversor central (`StripeAmountConverter` ou equivalente) é RISK alto: erro gera cobrança 100x maior ou menor.
- Fallback silencioso para zero (`?: 0`, `?: BigDecimal.ZERO`, `getOrDefault(..., 0)`) em preço/valor ausente é BUG: o padrão é falhar explicitamente.
Cite a linha e o tipo usado como evidência.

---
name: arquitetura-hexagonal
description: domínio isolado de framework e infraestrutura
groups: [DOMAIN, APPLICATION]
priority: 40
---
- Domínio (`domain/`) não importa Spring, JPA (`jakarta.persistence`), SDK do GCP, Stripe ou clientes HTTP. Import desse tipo no domínio é ARCHITECTURE com evidência no import.
- Caso de uso depende de port (interface); adapter de infraestrutura implementa o port. Caso de uso instanciando adapter concreto ou SDK é desvio.
- Regra de negócio em controller, consumer ou repositório é desvio; aponte para onde ela deveria estar.
Não proponha camadas, padrões ou abstrações novas sem problema concreto.

---
name: integracao-http
description: clientes HTTP síncronos (RestClient, WebClient, Feign)
groups: [INTEGRATION]
priority: 55
---
- Retry só em operação idempotente; POST de cobrança/pagamento sem idempotency key + retry = cobrança duplicada.
- Ordem com persistência: chamada externa confirmada e `save()` local falhando deixa estado divergente — pergunte como é reconciliado.
- Erro do fornecedor traduzido para exceção da aplicação; não vazar exceção do SDK para o caso de uso.
- Integração B2B assinada por interceptor HMAC com timestamp: request novo que contorna o interceptor (client criado à mão) perde autenticação/anti-replay.
- Não logar corpo de request/response com dado pessoal ou token.

---
name: migracao-postgres
description: Flyway/Liquibase em PostgreSQL com deploy rolling no GKE
groups: [MIGRATION]
priority: 60
---
Durante o deploy rolling, a versão anterior da aplicação roda contra o schema novo. Procure:
- `ADD COLUMN ... NOT NULL` sem `DEFAULT` em tabela populada;
- `DROP COLUMN`/`RENAME COLUMN`/mudança de tipo usada pela versão anterior — exige expand/contract em dois deploys;
- `CREATE INDEX` sem `CONCURRENTLY` em tabela grande (bloqueia escrita); lembre que `CONCURRENTLY` não roda dentro de transação da migration;
- `UPDATE` em massa sem lote;
- divergência entre migration e entidade (nome, tipo, `nullable`, tamanho).

---
name: jpa-kotlin
description: entidades JPA e repositórios em Kotlin
groups: [PERSISTENCE]
paths: ["**/*.kt"]
priority: 45
---
- `data class` como `@Entity`: `equals`/`hashCode` por todos os campos quebram `Set` e coleções lazy; prefira por id.
- Tipo Kotlin não-nulo mapeado em coluna anulável (ou o contrário) quebra na leitura de dado existente.
- Acesso a associação lazy dentro de loop = N+1; informe o volume em que pesa.
- Entidade auditada (`@Audited`, Envers): campo novo sem auditoria quando o restante é auditado merece pergunta.
- `findAll()`/query sem paginação em tabela que cresce com clientes.

---
name: kotlin-idiomas-de-risco
description: construções Kotlin que escondem falha
paths: ["**/*.kt"]
triggers: ["!!", "runCatching", "catch (", "lateinit", "GlobalScope"]
priority: 35
---
- `!!` em valor vindo de fora (payload, banco, API) vira NPE em produção; aponte a origem do valor.
- `runCatching`/`catch (e: Exception)` que engole erro sem log nem propagação; em coroutine, também captura `CancellationException` e impede o cancelamento.
- `GlobalScope.launch` em fluxo de request: trabalho órfão, sem tratamento de erro.
- `lateinit` acessado antes da inicialização em bean com ciclo de vida não trivial.

---
name: dados-pessoais-em-log
description: LGPD em logs e respostas
triggers: ["log.", "logger.", "println", "cpf", "cnpj", "email", "token", "senha", "password"]
priority: 50
---
- Log com CPF, CNPJ de pessoa física, e-mail, telefone, token, segredo ou payload completo de cliente é SECURITY.
- Resposta de API expondo campo sensível que antes não era exposto é SECURITY/API_CONTRACT.
Não reporte log de identificadores técnicos (id de fatura, id de mensagem, correlation id) — são desejáveis.

---
name: testes-relevantes
description: o que importa nos testes deste stack
groups: [TEST]
priority: 30
---
Avalie se o teste protege o comportamento novo, não o estilo. Cenários que importam aqui:
- mensagem duplicada no consumer produz um único efeito;
- falha entre persistência e outbox/chamada externa;
- conversão monetária nos limites (centavos, arredondamento, valor zero, valor ausente);
- migração com dado pré-existente.
Mock que devolve exatamente o que a asserção verifica não testa nada — vale apontar.
Nunca gere finding de nome de teste, formatação ou biblioteca de asserção.
