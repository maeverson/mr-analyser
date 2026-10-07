package com.mranalyser

import com.github.ajalt.clikt.core.subcommands
import com.mranalyser.cli.AnalyseCommand
import com.mranalyser.cli.ConfigCommand
import com.mranalyser.cli.KbCommand
import com.mranalyser.cli.withKbSubcommands
import com.mranalyser.cli.MrAnalyserCli
import com.mranalyser.cli.VersionCommand
import com.mranalyser.cli.withSubcommands

fun main(args: Array<String>) {
    MrAnalyserCli()
        .subcommands(
            AnalyseCommand(),
            ConfigCommand().withSubcommands(),
            KbCommand().withKbSubcommands(),
            VersionCommand()
        )
        .main(args)
}
