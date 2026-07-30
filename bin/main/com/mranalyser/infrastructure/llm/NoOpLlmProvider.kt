package com.mranalyser.infrastructure.llm

import com.mranalyser.application.port.LlmProvider
import com.mranalyser.application.port.LlmRequest
import com.mranalyser.application.port.LlmResponse

/**
 * Usado quando não há LLM configurado. A análise degrada para regras estáticas e sinais
 * arquiteturais, e o relatório é marcado como parcial.
 */
class NoOpLlmProvider : LlmProvider {
    override val name: String = "noop"

    override suspend fun complete(request: LlmRequest): LlmResponse =
        LlmResponse.failed("provider de LLM não configurado (etapa ${request.purpose.label} ignorada)")
}
