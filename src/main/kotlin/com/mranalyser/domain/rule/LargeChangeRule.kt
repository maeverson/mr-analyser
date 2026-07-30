package com.mranalyser.domain.rule

import com.mranalyser.domain.model.CommentType
import com.mranalyser.domain.model.FindingOrigin
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewCategory
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Sinaliza arquivo com alteração desproporcional.
 *
 * É informação sobre o **processo de revisão**, não sobre o código: um arquivo com muitas linhas
 * alteradas reduz a confiança da própria análise, porque o diff é truncado nos limites de
 * contexto. Por isso entra como observação de escopo, sem comentário de GitLab e nunca
 * bloqueante — quebrar o MR é decisão do time, não da ferramenta.
 *
 * Arquivos gerados e de build são ignorados: `package-lock.json` com 3.000 linhas é normal.
 */
class LargeChangeRule(
    private val maxLinesPerFile: Int = 1_500
) : ReviewRule {
    override val name: String = "large-change"

    override fun supports(context: RuleContext): Boolean =
        !context.change.generated &&
            context.group.isProductionCode &&
            context.change.totalLines > maxLinesPerFile

    override fun analyse(context: RuleContext): List<ReviewFinding> {
        val total = context.change.totalLines

        return listOf(
            ReviewFinding(
                severity = Severity.INFO,
                category = ReviewCategory.MAINTAINABILITY,
                type = FindingType.SUGGESTION,
                file = context.change.path,
                line = null,
                title = "Arquivo com $total linhas alteradas",
                description = "Este arquivo concentra $total linhas alteradas, acima do limite de " +
                    "$maxLinesPerFile configurado. Diffs desse tamanho são truncados no envio ao modelo, " +
                    "portanto a análise automatizada deste arquivo é parcial.",
                evidence = "${context.change.path}: +${context.change.linesAdded}/-${context.change.linesRemoved}.",
                failureScenario = null,
                impact = "Revisão manual mais difícil e cobertura reduzida da análise automatizada neste arquivo.",
                recommendation = "Revisar este arquivo com atenção adicional; avaliar particionamento em " +
                    "MRs menores se a mudança contiver preocupações independentes.",
                suggestedComment = null,
                commentType = CommentType.OBSERVATION,
                origin = FindingOrigin.STATIC_RULE,
                confidence = 0.95
            )
        )
    }
}
