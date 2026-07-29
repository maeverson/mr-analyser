package com.mranalyser.cli

import com.github.ajalt.clikt.core.CliktCommand

class VersionCommand : CliktCommand(name = "version") {
    override fun run() {
        echo("mr-analyser 0.1.0")
    }
}
