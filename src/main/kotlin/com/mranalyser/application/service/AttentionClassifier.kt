package com.mranalyser.application.service

import com.mranalyser.domain.model.AttentionLevel
import com.mranalyser.domain.model.ChangeGroup
import com.mranalyser.domain.model.FindingType
import com.mranalyser.domain.model.ReviewFinding
import com.mranalyser.domain.model.Severity

/**
 * Separa "onde ter cuidado" de "itens menores" por critério objetivo: severidade, tipo e o grupo
 * do arquivo. Substitui a supressão de ruído por texto: em double-check de time experiente,
 * esconder um item custa mais que mostrá-lo em uma linha. Pergunta sobre configuração, build,
 * documentação ou teste ("o timeout padrão de 5s é suficiente?") vira item menor sem sumir.
 */
class AttentionClassifier {

    fun classify(finding: ReviewFinding, groupOf: (String) -> ChangeGroup?): ReviewFinding {
        val minor = when {
            finding.blocking -> false
            !finding.severity.atLeast(Severity.MEDIUM) -> true
            finding.type == FindingType.SUGGESTION -> true
            finding.type == FindingType.QUESTION && finding.file?.let(groupOf) in PERIPHERAL_GROUPS -> true
            else -> false
        }
        return finding.copy(attention = if (minor) AttentionLevel.MINOR else AttentionLevel.CAREFUL)
    }

    private companion object {
        val PERIPHERAL_GROUPS = setOf(
            ChangeGroup.CONFIGURATION,
            ChangeGroup.BUILD,
            ChangeGroup.DOCUMENTATION,
            ChangeGroup.TEST
        )
    }
}
