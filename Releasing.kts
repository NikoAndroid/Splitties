/*
* Copyright (c) 2019. Louis Cognault Ayeva Derman
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

import java.io.File
import java.util.concurrent.TimeUnit
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.regex.Matcher
import java.util.regex.Pattern

val dir = File(".")


fun processBuilder(rawCommand: String, workingDir: File = dir): ProcessBuilder {
    val command = Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(rawCommand).let { m ->
        generateSequence {
            when {
                m.find() -> if (m.group(1) != null) m.group(1) else m.group(2)
                else -> null
            }
        }
    }.toList()
    return ProcessBuilder(command)
        .directory(workingDir)
        .redirectOutput(ProcessBuilder.Redirect.PIPE)
        .redirectError(ProcessBuilder.Redirect.PIPE)
}

fun String.execute(workingDir: File = dir): String {
    val proc = processBuilder(
        rawCommand = this,
        workingDir = workingDir
    ).start()
    proc.waitFor(60, TimeUnit.MINUTES)
    return proc.inputStream.use { it.bufferedReader().readText() }.also {
        val exitValue = proc.exitValue()
        if (exitValue != 0) {
            throw Exception("Non zero exit value: $exitValue")
        }
    }
}

fun String.executeAndPrint(workingDir: File = dir) {
    val proc = processBuilder(
        rawCommand = this,
        workingDir = workingDir
    )
        .redirectInput(ProcessBuilder.Redirect.INHERIT)
        .redirectOutput(ProcessBuilder.Redirect.INHERIT)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    proc.waitFor(60, TimeUnit.MINUTES)
    val exitValue = proc.exitValue()
    if (exitValue != 0) {
        throw Exception("Non zero exit value: $exitValue")
    }
}

fun requestUserConfirmation(yesNoQuestion: String) {
    println("$yesNoQuestion Y/n")
    readLine()?.trimEnd().let { input ->
        if (input != "Y" && !"yes".equals(input, ignoreCase = true)) {
            println("Process aborted."); throw CancellationException()
        }
    }
}

fun releasingNonSnapshot() {
    val currentBranch = "git rev-parse --abbrev-ref HEAD".execute().trimEnd()
    check(currentBranch == "develop") { "Please, checkout the `develop` branch first." }
    val versionsFile = dir.resolve("buildSrc/src/main/kotlin/ProjectVersions.kt")
    val libVersionLineStart = "    const val thisLibrary = \""
    val libraryVersionLine = versionsFile.readLines().singleOrNull { line ->
        line.startsWith(libVersionLineStart)
    } ?: throw IllegalStateException("Library version line not found.")
    val currentSnapshotVersion = libraryVersionLine.substring(
        startIndex = libVersionLineStart.length,
        endIndex = libraryVersionLine.lastIndex
    ).also { versionName ->
        check(versionName.endsWith("-SNAPSHOT")) {
            "Version in ${versionsFile.path} should be a `-SNAPSHOT` version."
        }
    }
    println("Current version: $currentSnapshotVersion")
    println("Please enter the name of the new version you want to release:")
    val newVersion = readLine()?.trimEnd().also { input ->
        when {
            input.isNullOrEmpty() -> throw IllegalStateException("No version entered.")
            input.any { it == ' ' } -> throw IllegalStateException("Versions can't contain spaces.")
            input.startsWith('v') -> throw IllegalStateException("Please, don't include v prefix.")
            !input.first().isDigit() -> throw IllegalStateException("Should start with a digit.")
            !input.all {
                it.isLetterOrDigit() || it == '.' || it == '-'
            } -> throw IllegalStateException("Only digits, letters, dots and dashes are allowed.")
            input.contains("-SNAPSHOT") -> throw IllegalStateException("Snapshots not allowed")
        }
        val existingVersions = "git tag".execute().trimEnd().lineSequence().filter {
            it.startsWith("v") && it.getOrElse(1) { ' ' }.isDigit()
        }.sorted().toList()
        check("v$input" !in existingVersions) { "This version already exists!" }
    }!!
    println("New version: \"$newVersion\"")
    requestUserConfirmation("Confirm?")
    val versionsFileTextBeforeEdits = versionsFile.readText()
    versionsFile.writeText(
        versionsFileTextBeforeEdits.replace(
            oldValue = libraryVersionLine,
            newValue = "$libVersionLineStart$newVersion\""
        )
    )
    println("Update the `README.md` with the new version and any other changes.")
    requestUserConfirmation("Done?")
    println("Update the `CHANGELOG.md` for the impending release.")
    requestUserConfirmation("Done?")
    "git commit -am \"Prepare for release $newVersion\"".executeAndPrint()
    "git tag -a v$newVersion -m \"Version $newVersion\"".executeAndPrint()
    val cleanAndUploadCommand = "./gradlew clean bintrayUpload"
    println("Running `$cleanAndUploadCommand`")
    cleanAndUploadCommand.executeAndPrint()
    println("Please check upload succeded.")
    val pushToOriginCommand = "git push origin"
    println("Will now run $pushToOriginCommand")
    requestUserConfirmation("Continue?")
    pushToOriginCommand.executeAndPrint()
    println("Create a pull request from the `develop` to the `master` branch on GitHub for the new version, if not already done.")
    requestUserConfirmation("Done?")
    println("Sign in on Bintray and publish the packages.")
    requestUserConfirmation("Done?")
    val pushToOriginWithTagsCommand = "git push origin --tags"
    println("Will now run $pushToOriginWithTagsCommand")
    pushToOriginWithTagsCommand.executeAndPrint()
    requestUserConfirmation("Continue?")
    println("Merge the pull request for the new version on GitHub.")
    requestUserConfirmation("Done?")
    println("Publish release on GitHub.")
    requestUserConfirmation("Done?")
    println("Will now checkout the `master` branch, pull from GitHub (origin) to update the local `master` branch.")
    requestUserConfirmation("Continue?")
    "git checkout master".executeAndPrint()
    "git pull origin".executeAndPrint()
    println("About to checkout the develop branch (and update it from master for merge commits).")
    requestUserConfirmation("Continue?")
    "git checkout develop".executeAndPrint()
    "git merge master".executeAndPrint()
    println("Let's update the library for next development version.")
    println("If you want to keep using $currentSnapshotVersion, enter an empty line.")
    println("Otherwise, enter the name of the next target version (`-SNAPSHOT` will be added automatically)")
    val nextDevVersion: String = readLine().let { input ->
        if (input.isNullOrBlank()) currentSnapshotVersion else "$input-SNAPSHOT"
    }
    versionsFile.writeText(
        versionsFileTextBeforeEdits.replace(
            oldValue = libraryVersionLine,
            newValue = "$libVersionLineStart$nextDevVersion\""
        )
    )
    println("${versionsFile.path} has been edited with next developement version ($nextDevVersion).")
    val nextDevVersionCommitCommand = "git commit -am \"Prepare next development version.\""
    requestUserConfirmation("Will run $nextDevVersionCommitCommand Continue?")
    nextDevVersionCommitCommand.executeAndPrint()

    requestUserConfirmation("Finally the last step: Running: `$pushToOriginCommand`. Continue?")
    pushToOriginCommand.executeAndPrint()
    println("All Done! Let's brag about this new release!!")
}

fun openUrl(url: String) {
    val osName = System.getProperty("os.name").toLowerCase()
    val isMacOs: Boolean = "mac" in osName
    val command = if (isMacOs) "open $url" else {
        val isWindows: Boolean = "win" in osName
        if (isWindows) {
            """start "" "$url""""
        } else "xdg-open $url"
    }
    command.execute()
}
releasingNonSnapshot()
//openUrl("https://stackoverflow.com/a/48266060/4433326")